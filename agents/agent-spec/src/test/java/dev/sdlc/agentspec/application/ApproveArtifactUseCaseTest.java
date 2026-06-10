package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.domain.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ApproveArtifactUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");
    static final String FILE = """
            ---
            id: SPEC-0001
            type: Specification
            title: 'Checkout tax'
            status: PROPOSED
            derivesFrom: [REQ-0012]
            provenance:
              sourceRefs: ['REQ-0012@r1']
              generatedBy: 'agent-spec@v1'
              confidence: 0.80
              assumptions: []
              humanApproved: false
            ---
            ## Acceptance criteria

            Scenario: FR VAT
            Given a FR cart
            When checkout
            Then VAT added
            """;

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    Map<String, String> files = new HashMap<>();
    ArtifactRepositoryPort repo = new ArtifactRepositoryPort() {
        public String write(String path, String content) {
            files.put(path, content);
            return FrontmatterParser.gitBlobSha(content);
        }
        public Optional<String> read(String path) { return Optional.ofNullable(files.get(path)); }
    };
    ArtifactId specId = ArtifactId.of("SPEC-0001");

    @BeforeEach
    void proposedSpec() {
        files.put("specs/SPEC-0001.md", FILE);
        var prov = Provenance.generated(List.of("REQ-0012@r1"), "agent-spec@v1", 0.8, List.of());
        graph.upsert(new Node(specId, NodeType.SPECIFICATION, "Checkout tax", "specs/SPEC-0001.md",
                FrontmatterParser.gitBlobSha(FILE), NodeStatus.PROPOSED, 1, prov, T0, T0));
    }

    HumanInTheLoopPort decide(boolean approved, String feedback) {
        return new HumanInTheLoopPort() {
            public String askClarifyingQuestion(String q) { return ""; }
            public ApprovalDecision requestApproval(ArtifactId a, String s) {
                return new ApprovalDecision(approved, "a.dupont", feedback);
            }
        };
    }

    @Test
    void approvalTransitionsGraphAndFileWithRecordedApprover() {
        var result = new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0).review(specId);

        var node = graph.get(specId).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeStatus.APPROVED);
        assertThat(node.provenance().humanApproved()).isTrue();
        assertThat(node.provenance().approvedBy()).isEqualTo("a.dupont");
        assertThat(result.approved()).isTrue();

        // the FILE is canonical: it must reflect the approval and survive a rebuild
        var content = files.get("specs/SPEC-0001.md");
        var reparsed = new FrontmatterParser().parse(content, "specs/SPEC-0001.md");
        assertThat(reparsed.node().status()).isEqualTo(NodeStatus.APPROVED);
        assertThat(reparsed.node().provenance().humanApproved()).isTrue();
        assertThat(reparsed.node().provenance().approvedBy()).isEqualTo("a.dupont");
        // graph node carries the rewritten file's sha
        assertThat(node.blobSha()).isEqualTo(FrontmatterParser.gitBlobSha(content));
        // body untouched
        assertThat(content).contains("Scenario: FR VAT");
    }

    @Test
    void rejectionReturnsToDraftInGraphAndFileAndKeepsFeedback() {
        var result = new ApproveArtifactUseCase(graph, repo, decide(false, "criteria too vague"), () -> T0)
                .review(specId);

        assertThat(graph.get(specId).orElseThrow().status()).isEqualTo(NodeStatus.DRAFT);
        assertThat(result.feedback()).isEqualTo("criteria too vague");
        var reparsed = new FrontmatterParser().parse(files.get("specs/SPEC-0001.md"), "x.md");
        assertThat(reparsed.node().status()).isEqualTo(NodeStatus.DRAFT);
        assertThat(reparsed.node().provenance().humanApproved()).isFalse();
    }

    @Test
    void onlyProposedArtifactsCanBeReviewed() {
        graph.upsert(graph.get(specId).orElseThrow().withStatus(NodeStatus.DRAFT,
                graph.get(specId).orElseThrow().provenance(), T0));
        assertThatThrownBy(() -> new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0)
                .review(specId))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROPOSED");
    }

    HumanInTheLoopPort decideAs(String reviewer) {
        return new HumanInTheLoopPort() {
            public String askClarifyingQuestion(String q) { return ""; }
            public ApprovalDecision requestApproval(ArtifactId a, String s) {
                return new ApprovalDecision(true, reviewer, null);
            }
        };
    }

    @Test
    void reviewerNamesWithQuotesAndRegexMetacharactersRoundTrip() {
        var result = new ApproveArtifactUseCase(graph, repo, decideAs("o'brien$1\\x"), () -> T0).review(specId);

        assertThat(result.approved()).isTrue();
        var reparsed = new FrontmatterParser().parse(files.get("specs/SPEC-0001.md"), "x.md");
        assertThat(reparsed.node().provenance().approvedBy()).isEqualTo("o'brien$1\\x");
    }

    @Test
    void missingArtifactFileFailsLoudlyInsteadOfSilentlyDiverging() {
        files.clear(); // graph says PROPOSED but no canonical file exists
        assertThatThrownBy(() -> new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0)
                .review(specId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("specs/SPEC-0001.md");
    }
}
