package dev.sdlc.agentspec.application;

import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.domain.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class RevalidateArtifactUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String OLD_SHA = "a".repeat(40);

    @TempDir
    Path dir;

    InMemoryTraceabilityGraph graph;
    FileArtifactRepository repo;

    @BeforeEach
    void staleSpec() throws IOException {
        graph = new InMemoryTraceabilityGraph();
        repo = new FileArtifactRepository(dir);

        // REQ file: has new content whose sha != OLD_SHA (pinned in spec)
        String reqContent = "---\nid: REQ-0012\ntype: Requirement\ntitle: r\nstatus: APPROVED\n"
                + "provenance:\n  sourceRefs: [t]\n  generatedBy: h\n  confidence: 1.0\n"
                + "  assumptions: []\n  humanApproved: true\n  approvedBy: a.dupont\n---\nnew content\n";
        Files.createDirectories(dir.resolve("requirements"));
        Files.writeString(dir.resolve("requirements/REQ-0012.md"), reqContent);

        // SPEC file: pins OLD_SHA which differs from REQ's actual current sha → stale after rebuild
        String specContent = ("---\nid: SPEC-0001\ntype: Specification\ntitle: s\nstatus: APPROVED\n"
                + "derivesFrom: ['REQ-0012@%s']\n"
                + "provenance:\n  sourceRefs: ['REQ-0012@%s']\n  generatedBy: agent-spec@v1\n"
                + "  confidence: 0.8\n  assumptions: []\n  humanApproved: true\n  approvedBy: a.dupont\n"
                + "---\nbody\n").formatted(OLD_SHA, OLD_SHA);
        Files.createDirectories(dir.resolve("specs"));
        Files.writeString(dir.resolve("specs/SPEC-0001.md"), specContent);

        // Mirror a rebuild that found the pin stale
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);
    }

    @Test
    void revalidationRestampsPinAndClearsFlag() {
        var useCase = new RevalidateArtifactUseCase(graph, repo, () -> T0);
        useCase.revalidate(ArtifactId.of("SPEC-0001"), ArtifactId.of("REQ-0012"), "a.dupont");

        // (a) file pin updated to the upstream's CURRENT sha
        String reqContent;
        try {
            reqContent = Files.readString(dir.resolve("requirements/REQ-0012.md"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String reqSha = FrontmatterParser.gitBlobSha(reqContent);
        String specContent;
        try {
            specContent = Files.readString(dir.resolve("specs/SPEC-0001.md"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertThat(specContent).contains("REQ-0012@" + reqSha);

        // (b) graph: staleNodes empty + spec restored APPROVED
        assertThat(graph.staleNodes()).isEmpty();
        assertThat(graph.get(ArtifactId.of("SPEC-0001")).orElseThrow().status())
                .isEqualTo(NodeStatus.APPROVED);

        // (c) graph node sha == rewritten file's sha
        assertThat(graph.get(ArtifactId.of("SPEC-0001")).orElseThrow().blobSha())
                .isEqualTo(FrontmatterParser.gitBlobSha(specContent));
    }

    @Test
    void revalidatingAlreadyCurrentPinIsNoOp() throws IOException {
        // Build a spec that pins the CURRENT sha of the req (no staleness)
        String reqContent = Files.readString(dir.resolve("requirements/REQ-0012.md"));
        String currentSha = FrontmatterParser.gitBlobSha(reqContent);

        // Overwrite spec file with correct pin
        String freshSpec = ("---\nid: SPEC-0001\ntype: Specification\ntitle: s\nstatus: APPROVED\n"
                + "derivesFrom: ['REQ-0012@%s']\n"
                + "provenance:\n  sourceRefs: ['REQ-0012@%s']\n  generatedBy: agent-spec@v1\n"
                + "  confidence: 0.8\n  assumptions: []\n  humanApproved: true\n  approvedBy: a.dupont\n"
                + "---\nbody\n").formatted(currentSha, currentSha);
        Files.writeString(dir.resolve("specs/SPEC-0001.md"), freshSpec);

        // Rebuild graph to a clean (no stale) state
        var cleanGraph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, cleanGraph);
        assertThat(cleanGraph.staleNodes()).isEmpty();

        String specShaBeforeRevalidate = FrontmatterParser.gitBlobSha(freshSpec);
        var useCase = new RevalidateArtifactUseCase(cleanGraph, repo, () -> T0);
        useCase.revalidate(ArtifactId.of("SPEC-0001"), ArtifactId.of("REQ-0012"), "a.dupont");

        // File unchanged (no rewrite happened)
        String specContentAfter = Files.readString(dir.resolve("specs/SPEC-0001.md"));
        assertThat(FrontmatterParser.gitBlobSha(specContentAfter)).isEqualTo(specShaBeforeRevalidate);
        // Status unchanged
        assertThat(cleanGraph.get(ArtifactId.of("SPEC-0001")).orElseThrow().status())
                .isEqualTo(NodeStatus.APPROVED);
        // Still no stale nodes
        assertThat(cleanGraph.staleNodes()).isEmpty();
    }
}
