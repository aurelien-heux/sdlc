package dev.sdlc.agenttestgen.application;

import dev.sdlc.agent.*;
import dev.sdlc.agent.port.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.domain.event.SdlcEvent;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GenerateTestsUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String SPEC_SHA = "e".repeat(40);

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
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    static final String SPEC_FILE = """
            ---
            id: SPEC-0001
            type: Specification
            title: 'Checkout tax'
            status: APPROVED
            derivesFrom: ['REQ-0001@%s']
            provenance:
              sourceRefs: ['REQ-0001@%s']
              generatedBy: 'agent-spec@v1'
              confidence: 0.80
              assumptions: []
              humanApproved: true
              approvedBy: 'a.dupont'
            ---
            ## Acceptance criteria

            Scenario: FR VAT
            Given a FR cart
            When checkout
            Then VAT added
            """.formatted("a".repeat(40), "a".repeat(40));

    static final String STORY_FILE = """
            ---
            id: STORY-0001
            type: BacklogItem
            title: 'Rate lookup'
            status: APPROVED
            level: story
            estimate: M
            acceptanceHook: 'FR VAT'
            derivesFrom: ['SPEC-0001@%s']
            provenance:
              sourceRefs: ['SPEC-0001@%s']
              generatedBy: 'agent-backlog@v1'
              confidence: 0.7
              assumptions: []
              humanApproved: true
              approvedBy: 'a.dupont'
            ---
            level: story
            """.formatted(SPEC_SHA, SPEC_SHA);

    Node approved(String id, NodeType type, String path, String sha) {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of()).approve("a.dupont", T0);
        return new Node(ArtifactId.of(id), type, id, path, sha, NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    GenerateTestsUseCase useCase(LanguageModelPort model) {
        graph.upsert(approved("SPEC-0001", NodeType.SPECIFICATION, "specs/SPEC-0001.md", SPEC_SHA));
        graph.upsert(approved("STORY-0001", NodeType.BACKLOG_ITEM, "backlog/STORY-0001.md", "f".repeat(40)));
        files.put("specs/SPEC-0001.md", SPEC_FILE);
        files.put("backlog/STORY-0001.md", STORY_FILE);
        return new GenerateTestsUseCase(model, graph, repo, published::add, noTrace,
                new StepSkeletonParser(), "agent-testgen@v1", new Guardrails(5, 1.0), "java");
    }

    static final String SKELETON_JSON = """
            {"language": "java", "content": "public class CheckoutTaxSteps { /* @Given ... */ }"}
            """;

    @Test
    void producesFeatureAndSkeletonArtifactsWithVerifiesEdges() {
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(SKELETON_JSON));

        var ids = useCase(model).generate(ArtifactId.of("SPEC-0001"));

        assertThat(ids).hasSize(2);
        var featureId = ids.getFirst();
        var stepsId = ids.get(1);
        // feature artifact: deterministic content, no model involvement for it
        var featureFile = files.get(graph.get(featureId).orElseThrow().repoPath());
        assertThat(featureFile).contains("Feature: Checkout tax")
                .contains("Scenario: FR VAT").contains("derivesFrom: ['SPEC-0001@" + SPEC_SHA + "']");
        // skeleton artifact from the model
        var stepsFile = files.get(graph.get(stepsId).orElseThrow().repoPath());
        assertThat(stepsFile).contains("CheckoutTaxSteps");
        // VERIFIES edges: both artifacts -> spec; feature -> story via acceptanceHook match
        assertThat(graph.downstreamOf(ArtifactId.of("SPEC-0001"), EdgeType.VERIFIES))
                .extracting(Node::id).containsExactlyInAnyOrder(featureId, stepsId);
        assertThat(graph.downstreamOf(ArtifactId.of("STORY-0001"), EdgeType.VERIFIES))
                .extracting(Node::id).containsExactly(featureId);
        // propagation spine: both DERIVE_FROM the spec
        assertThat(graph.downstreamOf(ArtifactId.of("SPEC-0001"), EdgeType.DERIVES_FROM))
                .extracting(Node::id).contains(featureId, stepsId);
        // events + status
        assertThat(published).contains(new ArtifactProposed(featureId), new ArtifactProposed(stepsId));
        assertThat(graph.get(featureId).orElseThrow().status()).isEqualTo(NodeStatus.PROPOSED);
        // the model prompt contained the feature text and design context marker
        assertThat(model.requests.getFirst().messages().getFirst().content())
                .contains("Scenario: FR VAT");
    }

    @Test
    void refusesUnapprovedSpec() {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of());
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "t",
                "specs/SPEC-0001.md", SPEC_SHA, NodeStatus.PROPOSED, 1, prov, T0, T0));
        var model = new FakeLanguageModel();

        assertThatThrownBy(() -> new GenerateTestsUseCase(model, graph, repo, published::add,
                noTrace, new StepSkeletonParser(), "agent-testgen@v1", new Guardrails(5, 1.0), "java")
                .generate(ArtifactId.of("SPEC-0001")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty();
    }
}
