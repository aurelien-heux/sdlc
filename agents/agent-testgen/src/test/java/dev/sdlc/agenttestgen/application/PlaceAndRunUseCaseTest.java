package dev.sdlc.agenttestgen.application;

import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.adapter.common.ProcessTestRunner;
import dev.sdlc.domain.*;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.InMemoryTraceabilityGraph;
import dev.sdlc.trace.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceAndRunUseCaseTest {
    static final Instant T1 = Instant.parse("2026-06-12T10:00:00Z");
    static final String SPEC_SHA = "e".repeat(40);

    // the two TEST artifacts exactly as GenerateTestsUseCase writes them (Task 4's output format)
    static final String FEATURE_FILE = """
            ---
            id: TEST-0001
            type: Test
            title: 'Checkout tax — feature'
            status: PROPOSED
            derivesFrom: ['SPEC-0001@%s']
            provenance:
              sourceRefs: ['SPEC-0001@%s']
              generatedBy: 'agent-testgen@v1'
              confidence: 0.90
              assumptions: []
              humanApproved: false
            ---
            Feature: Checkout tax

              Scenario: FR VAT
                Given a FR cart
                When checkout
                Then VAT added

            """.formatted(SPEC_SHA, SPEC_SHA);

    static final String STEPS_FILE = """
            ---
            id: TEST-0002
            type: Test
            title: 'Checkout tax — step skeletons'
            status: PROPOSED
            derivesFrom: ['SPEC-0001@%s']
            provenance:
              sourceRefs: ['SPEC-0001@%s']
              generatedBy: 'agent-testgen@v1'
              confidence: 0.70
              assumptions: ['skeletons are review drafts, not compile-verified']
              humanApproved: false
            ---
            ```java
            public class CheckoutTaxSteps { /* @Given ... */ }
            ```
            """.formatted(SPEC_SHA, SPEC_SHA);

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();

    Node proposed(String id, String repoPath, String sha) {
        var prov = Provenance.generated(List.of("SPEC-0001@" + SPEC_SHA), "agent-testgen@v1",
                0.8, List.of());
        return new Node(ArtifactId.of(id), NodeType.TEST, id, repoPath, sha,
                NodeStatus.PROPOSED, 1, prov, T1, T1);
    }

    PlaceAndRunUseCase useCase(FileArtifactRepository workspace, Path target, int exitCode) {
        return new PlaceAndRunUseCase(graph, workspace, target,
                "src/test/resources/features", "src/test/java/generated",
                new ProcessTestRunner(target, List.of("sh", "-c", "exit " + exitCode)), () -> T1);
    }

    @Test
    void placesPayloadsRunsAndRecordsResultInFrontmatter(@TempDir Path workspaceDir,
                                                         @TempDir Path target) throws Exception {
        var workspace = new FileArtifactRepository(workspaceDir);
        var featureSha = workspace.write("tests/TEST-0001.feature.md", FEATURE_FILE);
        var stepsSha = workspace.write("tests/TEST-0002.steps.md", STEPS_FILE);
        graph.upsert(proposed("TEST-0001", "tests/TEST-0001.feature.md", featureSha));
        graph.upsert(proposed("TEST-0002", "tests/TEST-0002.steps.md", stepsSha));

        var result = useCase(workspace, target, 0)
                .placeAndRun(ArtifactId.of("TEST-0001"), ArtifactId.of("TEST-0002"));

        assertThat(result.passed()).isTrue();
        // placement: feature body verbatim, skeleton with its code fence stripped, ext by language
        var placedFeature = Files.readString(
                target.resolve("src/test/resources/features/TEST-0001.feature"));
        assertThat(placedFeature).contains("Feature: Checkout tax")
                .contains("Scenario: FR VAT").doesNotContain("---");
        var placedSteps = Files.readString(
                target.resolve("src/test/java/generated/TEST-0002.java"));
        assertThat(placedSteps).contains("public class CheckoutTaxSteps")
                .doesNotContain("```");
        // run result recorded in BOTH artifacts' frontmatter, right after status:
        for (var path : List.of("tests/TEST-0001.feature.md", "tests/TEST-0002.steps.md")) {
            var rewritten = workspace.read(path).orElseThrow();
            assertThat(rewritten).contains("status: PROPOSED\nlastRun: passed\nlastRunAt: '" + T1 + "'");
            // node sha synced to the rewritten file (metadata change, no propagation)
            var node = graph.get(new FrontmatterParser().parse(rewritten, path).node().id()).orElseThrow();
            assertThat(node.blobSha()).isEqualTo(FrontmatterParser.gitBlobSha(rewritten));
            assertThat(node.status()).isEqualTo(NodeStatus.PROPOSED);
        }

        // second run (failing command) REPLACES the stamp — no duplicate lastRun lines
        var failed = useCase(workspace, target, 1)
                .placeAndRun(ArtifactId.of("TEST-0001"), ArtifactId.of("TEST-0002"));

        assertThat(failed.passed()).isFalse();
        for (var path : List.of("tests/TEST-0001.feature.md", "tests/TEST-0002.steps.md")) {
            var rewritten = workspace.read(path).orElseThrow();
            assertThat(rewritten).containsOnlyOnce("lastRun: failed")
                    .containsOnlyOnce("lastRunAt:").doesNotContain("lastRun: passed");
            assertThat(graph.get(new FrontmatterParser().parse(rewritten, path).node().id())
                    .orElseThrow().blobSha()).isEqualTo(FrontmatterParser.gitBlobSha(rewritten));
        }
    }
}
