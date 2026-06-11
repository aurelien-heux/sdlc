package dev.sdlc.governance;

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
            derivesFrom: ['REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa']
            provenance:
              sourceRefs: ['REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa']
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
        var prov = Provenance.generated(List.of("REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), "agent-spec@v1", 0.8, List.of());
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

    @Test
    void approvalRestampsDependentsPinsSoRebuildStaysClean() {
        // GOAL approved while REQ (same batch) pins GOAL's pre-approval sha: approval rewrites
        // GOAL's file (new sha), so REQ's derivesFrom pin must be re-stamped or the next
        // rebuild flags REQ as stale though nothing semantic changed (FR-TRACE-2).
        var goalFile = """
                ---
                id: GOAL-0001
                type: Goal
                title: 'Faster checkout'
                status: PROPOSED
                provenance:
                  sourceRefs: ['inbox/payment-notes.md']
                  generatedBy: 'agent-intent@v1'
                  confidence: 0.80
                  assumptions: []
                  humanApproved: false
                ---
                Reduce checkout time.
                """;
        var oldGoalSha = FrontmatterParser.gitBlobSha(goalFile);
        var reqFile = """
                ---
                id: REQ-0001
                type: Requirement
                title: 'Apply regional tax'
                status: PROPOSED
                derivesFrom: ['GOAL-0001@%s']
                provenance:
                  sourceRefs: ['GOAL-0001@%s']
                  generatedBy: 'agent-intent@v1'
                  confidence: 0.80
                  assumptions: []
                  humanApproved: false
                ---
                Tax by shipping region.
                """.formatted(oldGoalSha, oldGoalSha);
        files.put("intent/GOAL-0001.md", goalFile);
        files.put("intent/REQ-0001.md", reqFile);
        var goalId = ArtifactId.of("GOAL-0001");
        var reqId = ArtifactId.of("REQ-0001");
        graph.upsert(new Node(goalId, NodeType.GOAL, "Faster checkout", "intent/GOAL-0001.md",
                oldGoalSha, NodeStatus.PROPOSED, 1,
                Provenance.generated(List.of("inbox/payment-notes.md"), "agent-intent@v1", 0.8, List.of()),
                T0, T0));
        graph.upsert(new Node(reqId, NodeType.REQUIREMENT, "Apply regional tax", "intent/REQ-0001.md",
                FrontmatterParser.gitBlobSha(reqFile), NodeStatus.PROPOSED, 1,
                Provenance.generated(List.of("GOAL-0001@" + oldGoalSha), "agent-intent@v1", 0.8, List.of()),
                T0, T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, reqId, goalId, oldGoalSha, "agent-intent@v1", T0));

        new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0).review(goalId);

        var newGoalSha = graph.get(goalId).orElseThrow().blobSha();
        assertThat(newGoalSha).isNotEqualTo(oldGoalSha);
        // REQ's file now pins GOAL at its NEW sha — a rebuild from files alone stays clean
        var reqContent = files.get("intent/REQ-0001.md");
        assertThat(reqContent).contains("derivesFrom: ['GOAL-0001@" + newGoalSha + "']");
        // provenance keeps the ORIGINAL grounding sha: what the agent actually read
        assertThat(reqContent).contains("sourceRefs: ['GOAL-0001@" + oldGoalSha + "']");
        // REQ's node carries the rewritten file's sha (the re-stamp is itself a content change)
        assertThat(graph.get(reqId).orElseThrow().blobSha())
                .isEqualTo(FrontmatterParser.gitBlobSha(reqContent));
    }

    @Test
    void approvalPreservesPinnedRefs() {
        new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0).review(specId);
        assertThat(files.get("specs/SPEC-0001.md"))
                .contains("derivesFrom: ['REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa']");
    }

    static final class RecordingGit implements dev.sdlc.agent.port.GitPort {
        final java.util.List<String> merges = new java.util.ArrayList<>();
        public void ensureRepo() {}
        public boolean branchExists(String name) { return true; }
        public void checkoutBranch(String name, boolean createFromMain) {}
        public void commitAll(String message) {}
        public void merge(String branch, String message) { merges.add(branch + " :: " + message); }
        public String currentBranch() { return "main"; }
        public java.util.Optional<String> showFile(String branch, String path) { return java.util.Optional.empty(); }
        public java.util.List<String> branches(String prefix) { return java.util.List.of(); }
        public java.util.List<String> changedFiles(String branch) { return java.util.List.of(); }
    }

    @Test
    void approvalMergesTheProposalBranchWhenGitIsWired() {
        var git = new RecordingGit();
        new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0, git).review(specId);
        assertThat(git.merges).containsExactly("proposal/SPEC-0001 :: approval: SPEC-0001 by a.dupont");
    }

    @Test
    void rejectionDoesNotMerge() {
        var git = new RecordingGit();
        new ApproveArtifactUseCase(graph, repo, decide(false, "vague"), () -> T0, git).review(specId);
        assertThat(git.merges).isEmpty();
    }
}
