package dev.sdlc.agentspec.bootstrap;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.FakeLanguageModel;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ToolRegistry;
import dev.sdlc.adapter.common.ConsoleHumanInTheLoop;
import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.adapter.common.InProcessEventPublisher;
import dev.sdlc.adapter.common.LoggingRunTrace;
import dev.sdlc.agentspec.application.ArtifactChangedHandler;
import dev.sdlc.agentspec.application.GenerateSpecificationUseCase;
import dev.sdlc.agentspec.application.SpecDraftParser;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.NodeStatus;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.domain.event.RevalidationRequested;
import dev.sdlc.governance.ApproveArtifactUseCase;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.InMemoryTraceabilityGraph;
import dev.sdlc.trace.ProjectionBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndTest {
    static final String REQ = """
            ---
            id: REQ-0001
            type: Requirement
            title: Regional tax at checkout
            status: APPROVED
            provenance:
              sourceRefs: [ticket:PROJ-88]
              generatedBy: human
              confidence: 1.0
              assumptions: []
              humanApproved: true
              approvedBy: a.dupont
            ---
            Checkout must apply the tax rate of the shipping region.
            """;
    static final String MODEL_JSON = """
            {"title": "Checkout applies regional tax",
             "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
             "constraints": [], "assumptions": [], "untestable": []}
            """;

    @Test
    void phase0DefinitionOfDone(@TempDir Path workspace) throws Exception {
        // --- assemble (what bootstrap wiring does) ---
        Files.createDirectories(workspace.resolve("requirements"));
        Files.writeString(workspace.resolve("requirements/REQ-0001.md"), REQ);

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph);

        var repo = new FileArtifactRepository(workspace);
        var bus = new InProcessEventPublisher();
        bus.subscribe(e -> { if (e instanceof ArtifactChanged c)
                new ArtifactChangedHandler(graph, bus).on(c); });
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(MODEL_JSON));
        var loop = new AgentLoop(model, new ToolRegistry(List.of()),
                new LoggingRunTrace(), new Guardrails(5, 1.0));

        // --- 1. generate spec from approved requirement ---
        var specId = new GenerateSpecificationUseCase(loop, graph, repo, bus,
                new SpecDraftParser(), "agent-spec@v1")
                .generate(List.of(ArtifactId.of("REQ-0001")));
        assertThat(workspace.resolve("specs/" + specId.value() + ".md")).exists();
        assertThat(bus.log()).contains(new ArtifactProposed(specId));

        // --- 2. human approves through the gate ---
        var hitl = new ConsoleHumanInTheLoop(new BufferedReader(new StringReader("y\n")), "a.dupont");
        new ApproveArtifactUseCase(graph, repo, hitl, Instant::now).review(specId);
        assertThat(graph.get(specId).orElseThrow().status()).isEqualTo(NodeStatus.APPROVED);

        // --- 2b. restart survival: a FRESH projection rebuilt from the workspace files
        //     still shows the approval (the file is the canonical store).
        //     NOTE: this must run BEFORE step 3 — the NEEDS_REVALIDATION flag is
        //     projection state (files are not rewritten) and would not survive a rebuild.
        var rebuilt = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, rebuilt);
        var rebuiltSpec = rebuilt.get(specId).orElseThrow();
        assertThat(rebuiltSpec.status()).isEqualTo(NodeStatus.APPROVED);
        assertThat(rebuiltSpec.provenance().approvedBy()).isEqualTo("a.dupont");

        // --- 3. upstream requirement changes → spec flagged stale ---
        String changed = REQ.replace("shipping region", "billing region");
        String newSha = repo.write("requirements/REQ-0001.md", changed);
        bus.publish(new ArtifactChanged(ArtifactId.of("REQ-0001"), newSha));

        assertThat(graph.get(specId).orElseThrow().status()).isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        assertThat(bus.log()).anyMatch(e -> e instanceof RevalidationRequested r
                && r.subject().equals(specId));
        assertThat(graph.staleNodes()).extracting(n -> n.id().value()).contains(specId.value());

        // --- 4. restart-safe staleness: rebuilt projection flags spec stale WITHOUT applyChange ---
        // Step 3 wrote a new sha for REQ-0001 to disk; the spec's frontmatter still pins the OLD
        // sha. On rebuild, ProjectionBuilder detects pin != upstream current sha at startup time
        // and flags the spec NEEDS_REVALIDATION immediately — that is the entire point of
        // FR-TRACE-2 (restart-safe staleness via pinned refs). No applyChange call is needed.
        var rebuiltAfterChange = new InMemoryTraceabilityGraph();
        var rebuildEvents = new java.util.ArrayList<RevalidationRequested>();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, rebuiltAfterChange, rebuildEvents::add);
        // The spec is already stale at rebuild time — no applyChange needed
        assertThat(rebuiltAfterChange.get(specId).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        assertThat(rebuildEvents).extracting(e -> e.subject().value()).contains(specId.value());

        // --- 4b. the bootstrap wiring shape: rebuild publishes staleness INTO the bus ---
        var busAfterRestart = new InProcessEventPublisher();
        var graphAfterRestart = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser())
                .rebuild(workspace, graphAfterRestart, busAfterRestart::publish);
        assertThat(busAfterRestart.log())
                .anyMatch(e -> e instanceof RevalidationRequested r && r.subject().equals(specId));
    }
}
