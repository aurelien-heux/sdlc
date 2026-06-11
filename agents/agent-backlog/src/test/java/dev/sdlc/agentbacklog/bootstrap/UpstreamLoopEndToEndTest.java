package dev.sdlc.agentbacklog.bootstrap;

import dev.sdlc.adapter.common.ConsoleHumanInTheLoop;
import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.adapter.common.FileBacklogAdapter;
import dev.sdlc.adapter.common.InProcessEventPublisher;
import dev.sdlc.adapter.common.LoggingRunTrace;
import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.FakeLanguageModel;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ToolRegistry;
import dev.sdlc.agentbacklog.application.BacklogDraftParser;
import dev.sdlc.agentbacklog.application.GenerateBacklogUseCase;
import dev.sdlc.agentdesign.application.DesignDraftParser;
import dev.sdlc.agentdesign.application.GenerateDesignUseCase;
import dev.sdlc.agentintent.application.GenerateIntentUseCase;
import dev.sdlc.agentintent.application.IngestInboxUseCase;
import dev.sdlc.agentintent.application.IntentDraftParser;
import dev.sdlc.agentspec.application.GenerateSpecificationUseCase;
import dev.sdlc.agentspec.application.SpecDraftParser;
import dev.sdlc.agenttestgen.application.GenerateTestsUseCase;
import dev.sdlc.agenttestgen.application.StepSkeletonParser;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.EdgeType;
import dev.sdlc.domain.NodeStatus;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.domain.event.RevalidationRequested;
import dev.sdlc.governance.ApproveArtifactUseCase;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.InMemoryTraceabilityGraph;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.ProjectionBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1B definition-of-done (spec §12), extended by Phase 2 slice 1: the loop
 * UC-0001→UC-0004 plus test generation closes through ONE shared graph/workspace/bus,
 * every transition is human-gated, an upstream edit flags the full downstream chain
 * (TEST artifacts included — FR-TEST-5), and a restart reproduces the exact same
 * staleness AND the VERIFIES edges from files alone.
 */
class UpstreamLoopEndToEndTest {
    /** Same text as the repo's demo seed workspace/inbox/payment-notes.md (Task 12 README demo). */
    static final String STAKEHOLDER_NOTES = """
            # Payment notes — stakeholder meeting

            Right now we lose carts at checkout. Tax must match the region.
            Finance insists the rate applied at payment time follows the shipping region,
            not the storefront default.
            """;
    // Scripted model answers, one per agent run (copied from the per-module tests —
    // self-containment beats DRY in tests).
    static final String INTENT_JSON = """
            {"goals": [{"title": "Faster checkout", "description": "Reduce checkout time",
                        "sourceQuotes": ["we lose carts at checkout"], "assumptions": []}],
             "requirements": [{"title": "Apply regional tax", "description": "Tax by shipping region",
                        "kind": "functional", "moscow": "MUST", "goalTitle": "Faster checkout",
                        "sourceQuotes": ["Tax must match the region"], "assumptions": []}],
             "useCases": []}
            """;
    static final String SPEC_JSON = """
            {"title": "Checkout applies regional tax",
             "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
             "constraints": [], "assumptions": [], "untestable": []}
            """;
    static final String DESIGN_JSON = """
            {"elements": [{"title": "TaxCalculator", "description": "Pure domain service"}],
             "adrs": [{"title": "Outbox pattern", "context": "c", "decision": "outbox",
                       "alternatives": [{"option": "direct", "tradeoff": "lossy"},
                                        {"option": "CDC", "tradeoff": "heavy"}],
                       "consequences": ["relay"]}],
             "apiContracts": []}
            """;
    static final String BACKLOG_JSON = """
            {"items": [
              {"level": "epic", "title": "Checkout tax", "description": "all tax work",
               "acceptanceHook": null, "estimate": "L", "dependsOn": []},
              {"level": "story", "title": "Regional rate lookup", "description": "lookup by region",
               "acceptanceHook": "FR VAT", "estimate": "M", "dependsOn": ["Checkout tax"]}
            ]}
            """;
    // NOTE: the story's acceptanceHook above MUST equal SPEC_JSON's scenario name ("FR VAT") —
    // that is what lets testgen resolve the feature→story VERIFIES edge in step 4c.
    static final String SKELETON_JSON = """
            {"language": "java", "content": "public class CheckoutTaxSteps { /* @Given ... */ }"}
            """;

    @Test
    void upstreamLoopClosesAndSurvivesRestart(@TempDir Path workspace) throws Exception {
        // --- assemble: ONE graph, ONE file workspace, ONE bus shared by ALL agents,
        //     exactly as the bootstraps wire them in production ---
        Files.createDirectories(workspace.resolve("inbox"));
        Files.writeString(workspace.resolve("inbox/payment-notes.md"), STAKEHOLDER_NOTES);

        var graph = new InMemoryTraceabilityGraph();
        var repo = new FileArtifactRepository(workspace);
        var bus = new InProcessEventPublisher();
        bus.subscribe(e -> { if (e instanceof ArtifactChanged c)
                graph.applyChange(c.subject(), c.newBlobSha()).forEach(bus::publish); });
        var trace = new LoggingRunTrace();

        // --- 1. intent: inbox notes → GOAL + REQ, with one ask_human round-trip; approve both ---
        var intentModel = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("ask_human", Map.of("question", "how fast must checkout be?")),
                FakeLanguageModel.finalText(INTENT_JSON));
        var stakeholder = new ConsoleHumanInTheLoop(
                new BufferedReader(new StringReader("under 5 seconds\n")), "a.dupont");
        var generateIntent = new GenerateIntentUseCase(intentModel, stakeholder, graph, repo, bus,
                trace, new IntentDraftParser(), "agent-intent@v1", new Guardrails(12, 1.0));
        var intentIds = new IngestInboxUseCase(workspace, generateIntent::generate).ingest();

        assertThat(intentIds).hasSize(2);
        var goalId = byPrefix(intentIds, "GOAL");
        var reqId = byPrefix(intentIds, "REQ");
        assertThat(workspace.resolve("inbox/processed/payment-notes.md")).exists();
        approve(graph, repo, goalId);
        approve(graph, repo, reqId);

        // --- 2. spec from the approved REQ (the gate made it eligible); approve ---
        var specModel = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(SPEC_JSON));
        var specLoop = new AgentLoop(specModel, new ToolRegistry(List.of()), trace, new Guardrails(5, 1.0));
        var specId = new GenerateSpecificationUseCase(specLoop, graph, repo, bus,
                new SpecDraftParser(), "agent-spec@v1").generate(List.of(reqId));
        approve(graph, repo, specId);

        // --- 3. design from the approved spec: element + ADR with two alternatives; approve ---
        var designModel = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(DESIGN_JSON));
        var designIds = new GenerateDesignUseCase(designModel, graph, repo, bus, trace,
                new DesignDraftParser(), "agent-design@v1", new Guardrails(5, 1.0)).generate(specId);
        assertThat(designIds).hasSize(2);
        var desId = byPrefix(designIds, "DES");
        var adrId = byPrefix(designIds, "ADR");
        assertThat(repo.read(graph.get(adrId).orElseThrow().repoPath()).orElseThrow())
                .contains("## Alternatives").contains("CDC");
        approve(graph, repo, desId);
        approve(graph, repo, adrId);

        // --- 4. backlog from spec + designs: epic + dependent story; approve all ---
        var backlogModel = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(BACKLOG_JSON));
        var backlogIds = new GenerateBacklogUseCase(backlogModel, graph, repo,
                new FileBacklogAdapter(repo), bus, trace, new BacklogDraftParser(),
                "agent-backlog@v1", new Guardrails(5, 1.0)).generate(specId, List.of(desId, adrId));
        assertThat(backlogIds).hasSize(2);
        var epicId = byPrefix(backlogIds, "EPIC");
        var storyId = byPrefix(backlogIds, "STORY");
        assertThat(graph.downstreamOf(epicId, EdgeType.DEPENDS_ON))
                .extracting(Node::id).containsExactly(storyId);
        approve(graph, repo, epicId);
        approve(graph, repo, storyId);

        // --- 4c. testgen from the approved spec: deterministic feature + LLM step skeletons;
        //     the feature VERIFIES the spec AND the story (its acceptanceHook names the
        //     spec's "FR VAT" scenario); approve both ---
        var testgenModel = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(SKELETON_JSON));
        var testIds = new GenerateTestsUseCase(testgenModel, graph, repo, bus, trace,
                new StepSkeletonParser(), "agent-testgen@v1", new Guardrails(5, 1.0), "java")
                .generate(specId);
        assertThat(testIds).hasSize(2);
        var featureTestId = testIds.get(0);
        var stepsTestId = testIds.get(1);
        assertThat(graph.downstreamOf(specId, EdgeType.VERIFIES))
                .extracting(Node::id).containsExactlyInAnyOrder(featureTestId, stepsTestId);
        assertThat(graph.downstreamOf(storyId, EdgeType.VERIFIES))
                .extracting(Node::id).containsExactly(featureTestId);
        approve(graph, repo, featureTestId);
        approve(graph, repo, stepsTestId);
        assertThat(graph.staleNodes()).isEmpty(); // the whole chain is green before the edit

        // --- 5. propagation: edit the REQ → FULL downstream chain flagged, GOAL/REQ stay APPROVED ---
        var reqPath = graph.get(reqId).orElseThrow().repoPath();
        var original = repo.read(reqPath).orElseThrow();
        var edited = original.replace("shipping region", "billing region");
        assertThat(edited).isNotEqualTo(original);
        var newSha = repo.write(reqPath, edited);
        bus.publish(new ArtifactChanged(reqId, newSha));

        var liveStale = graph.staleNodes().stream()
                .map(n -> n.id().value()).collect(Collectors.toSet());
        assertThat(liveStale).containsExactlyInAnyOrder(specId.value(), desId.value(),
                adrId.value(), epicId.value(), storyId.value(),
                featureTestId.value(), stepsTestId.value()); // FR-TEST-5: TESTs ride the spine
        for (var flagged : List.of(specId, desId, adrId, epicId, storyId,
                featureTestId, stepsTestId)) {
            assertThat(bus.log()).anyMatch(e -> e instanceof RevalidationRequested r
                    && r.subject().equals(flagged));
        }
        assertThat(graph.get(goalId).orElseThrow().status()).isEqualTo(NodeStatus.APPROVED);
        assertThat(graph.get(reqId).orElseThrow().status()).isEqualTo(NodeStatus.APPROVED);

        // --- 6. restart parity: a FRESH projection rebuilt from the workspace files alone must
        //     flag the SAME id-set (FR-TRACE-2: pinned refs + the transitive pass reproduce
        //     live staleness), and the approvals must survive in the frontmatter ---
        var rebuilt = new InMemoryTraceabilityGraph();
        var restartBus = new InProcessEventPublisher();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, rebuilt, restartBus::publish);

        var rebuiltStale = rebuilt.staleNodes().stream()
                .map(n -> n.id().value()).collect(Collectors.toSet());
        assertThat(rebuiltStale).isEqualTo(liveStale);
        // VERIFIES edges are canonical frontmatter (`verifies:`) — the rebuilt projection
        // must reproduce them from the tests/ files alone, no live graph state involved
        assertThat(rebuilt.downstreamOf(specId, EdgeType.VERIFIES))
                .extracting(Node::id).containsExactlyInAnyOrder(featureTestId, stepsTestId);
        assertThat(rebuilt.downstreamOf(storyId, EdgeType.VERIFIES))
                .extracting(Node::id).containsExactly(featureTestId);
        // approvals survived: spot-check the spec's reviewer in the reparsed frontmatter
        var specNode = graph.get(specId).orElseThrow();
        var reparsedSpec = new FrontmatterParser().parse(
                Files.readString(workspace.resolve(specNode.repoPath())), specNode.repoPath());
        assertThat(reparsedSpec.node().provenance().humanApproved()).isTrue();
        assertThat(reparsedSpec.node().provenance().approvedBy()).isEqualTo("a.dupont");
        assertThat(rebuilt.get(goalId).orElseThrow().status()).isEqualTo(NodeStatus.APPROVED);
    }

    /** Fresh gate per approval: one scripted console "y", reviewer recorded in the frontmatter. */
    private static void approve(InMemoryTraceabilityGraph graph, FileArtifactRepository repo,
                                ArtifactId id) {
        var hitl = new ConsoleHumanInTheLoop(new BufferedReader(new StringReader("y\n")), "a.dupont");
        new ApproveArtifactUseCase(graph, repo, hitl, Instant::now).review(id);
        assertThat(graph.get(id).orElseThrow().status()).isEqualTo(NodeStatus.APPROVED);
    }

    private static ArtifactId byPrefix(List<ArtifactId> ids, String prefix) {
        return ids.stream().filter(i -> i.prefix().equals(prefix)).findFirst().orElseThrow();
    }
}
