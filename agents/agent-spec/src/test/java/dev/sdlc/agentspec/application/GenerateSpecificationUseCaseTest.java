package dev.sdlc.agentspec.application;

import dev.sdlc.agent.*;
import dev.sdlc.agent.port.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.domain.event.SdlcEvent;
import dev.sdlc.trace.*;
import dev.sdlc.trace.EdgeTarget;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GenerateSpecificationUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    List<SdlcEvent> published = new ArrayList<>();
    Map<String, String> files = new HashMap<>();

    ArtifactRepositoryPort repo = new ArtifactRepositoryPort() {
        public String write(String path, String content) {
            files.put(path, content);
            return FrontmatterParser.gitBlobSha(content);
        }
        public Optional<String> read(String path) { return Optional.ofNullable(files.get(path)); }
    };
    EventPublisherPort events = published::add;
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    Node approvedReq() {
        var prov = Provenance.generated(List.of("ticket:PROJ-88"), "human", 1.0, List.of())
                .approve("a.dupont", T0);
        return new Node(ArtifactId.of("REQ-0012"), NodeType.REQUIREMENT, "Regional tax",
                "requirements/REQ-0012.md", "r1", NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    String modelJson = """
            {"title": "Checkout applies regional tax",
             "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
             "constraints": [], "assumptions": ["single currency"], "untestable": []}
            """;

    GenerateSpecificationUseCase useCase(LanguageModelPort model) {
        files.put("requirements/REQ-0012.md", "---\nid: REQ-0012\n---\nRegional tax body");
        return new GenerateSpecificationUseCase(
                new AgentLoop(model, new ToolRegistry(List.of()), noTrace, new Guardrails(5, 1.0)),
                graph, repo, events, new SpecDraftParser(), "agent-spec@v1");
    }

    @Test
    void producesProposedSpecWithEdgesAndEvent() {
        graph.upsert(approvedReq());
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(modelJson));

        var specId = useCase(model).generate(List.of(ArtifactId.of("REQ-0012")));

        // node exists, PROPOSED, grounded provenance
        var spec = graph.get(specId).orElseThrow();
        assertThat(spec.status()).isEqualTo(NodeStatus.PROPOSED);
        assertThat(spec.provenance().humanApproved()).isFalse();
        assertThat(spec.provenance().sourceRefs()).contains("REQ-0012@r1");
        // file written with frontmatter + Gherkin
        var content = files.get(spec.repoPath());
        assertThat(content).startsWith("---").contains("Scenario: FR VAT");
        // DERIVES_FROM edge pins the upstream sha
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0012")))
                .extracting(Node::id).containsExactly(specId);
        // event emitted
        assertThat(published).containsExactly(new ArtifactProposed(specId));
        // prompt contained the requirement's file content
        assertThat(model.requests.getFirst().messages().getFirst().content())
                .contains("Regional tax body");
    }

    @Test
    void refusesUnapprovedInputs() {
        var draftReq = approvedReq().withStatus(NodeStatus.DRAFT,
                Provenance.generated(List.of("t"), "h", 1.0, List.of()), T0);
        graph.upsert(draftReq);
        var model = new FakeLanguageModel();

        assertThatThrownBy(() -> useCase(model).generate(List.of(ArtifactId.of("REQ-0012"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty(); // never called the model
    }

    @Test
    void writtenFileRoundTripsThroughFrontmatterParserEvenWithHostileTitle() {
        graph.upsert(approvedReq());
        String hostileJson = """
                {"title": "Checkout: apply [regional] tax # phase 0",
                 "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
                 "constraints": [], "assumptions": ["uses \\"latest\\" rates"], "untestable": []}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(hostileJson));

        var specId = useCase(model).generate(List.of(ArtifactId.of("REQ-0012")));

        var content = files.get(graph.get(specId).orElseThrow().repoPath());
        var reparsed = new FrontmatterParser().parse(content, "specs/" + specId.value() + ".md");
        assertThat(reparsed.node().title()).isEqualTo("Checkout: apply [regional] tax # phase 0");
        assertThat(reparsed.node().status()).isEqualTo(NodeStatus.PROPOSED);
        assertThat(reparsed.node().provenance().assumptions())
                .containsExactly("uses \"latest\" rates");
        assertThat(reparsed.edgeTargets().get(EdgeType.DERIVES_FROM))
                .extracting(EdgeTarget::id).containsExactly(ArtifactId.of("REQ-0012"));
    }

    @Test
    void untestableFlagsAreSurfacedInTheWrittenFile() {
        graph.upsert(approvedReq());
        String flaggedJson = """
                {"title": "Checkout tax",
                 "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
                 "constraints": [], "assumptions": ["x"],
                 "untestable": [{"id": "REQ-0012", "reason": "no measurable acceptance threshold"}]}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(flaggedJson));

        var specId = useCase(model).generate(List.of(ArtifactId.of("REQ-0012")));

        var content = files.get(graph.get(specId).orElseThrow().repoPath());
        assertThat(content).contains("## Testability flags")
                .contains("REQ-0012")
                .contains("no measurable acceptance threshold");
    }
}
