package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.NodeStatus;
import dev.sdlc.domain.event.RevalidationRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectionBuilderTest {
    @Test
    void rebuildsGraphFromArtifactDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("REQ-0012.md"), """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Checkout applies regional tax
                status: DRAFT
                derivesFrom: [REQ-0012]
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: false
                ---
                body
                """);

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);

        assertThat(graph.get(ArtifactId.of("SPEC-0007"))).isPresent();
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0012")))
                .extracting(n -> n.id().value()).containsExactly("SPEC-0007");
    }

    @Test
    void writeThenRebuildRoundTripsBlobShas(@TempDir Path dir) throws Exception {
        String req = """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """;
        String spec = """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: DRAFT
                derivesFrom: [REQ-0012]
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: false
                ---
                body
                """;
        Files.writeString(dir.resolve("REQ-0012.md"), req);
        Files.writeString(dir.resolve("SPEC-0007.md"), spec);
        String reqShaAtWrite = FrontmatterParser.gitBlobSha(req);

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);

        // sha computed at write time == sha computed at rebuild time (applyChange no-op contract)
        assertThat(graph.get(ArtifactId.of("REQ-0012")).orElseThrow().blobSha())
                .isEqualTo(reqShaAtWrite);
        // edge pinned the real upstream sha, not the "unknown" sentinel
        assertThat(graph.applyChange(ArtifactId.of("REQ-0012"), reqShaAtWrite)).isEmpty();
    }

    @Test
    void rebuildFlagsStalenessFromPinnedShas(@TempDir Path dir) throws Exception {
        String req = """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                EDITED while the system was down
                """;
        Files.writeString(dir.resolve("REQ-0012.md"), req);
        // spec pins a sha that is NOT the current content's sha → must come up stale
        String stalePin = "b".repeat(40);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: APPROVED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(stalePin, stalePin));

        var graph = new InMemoryTraceabilityGraph();
        var events = new ArrayList<RevalidationRequested>();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph, events::add);

        assertThat(graph.get(ArtifactId.of("SPEC-0007")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        assertThat(graph.staleNodes()).extracting(n -> n.id().value()).containsExactly("SPEC-0007");
        assertThat(events).extracting(e -> e.subject().value()).containsExactly("SPEC-0007");
    }

    @Test
    void rebuildWithMatchingPinsStaysClean(@TempDir Path dir) throws Exception {
        String req = """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """;
        Files.writeString(dir.resolve("REQ-0012.md"), req);
        String pin = FrontmatterParser.gitBlobSha(req);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: APPROVED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(pin, pin));

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);
        assertThat(graph.staleNodes()).isEmpty();
    }

    /**
     * Dangling pinned ref: the upstream node is NOT present in the workspace.
     * Per spec §5 staleness compares pin vs upstream's CURRENT sha; with no upstream node
     * the current sha is "unknown" — mismatch → STALE + NEEDS_REVALIDATION is the
     * conservative correct answer. The edge must be created (not silently dropped) and
     * no exception must be thrown.
     */
    @Test
    void rebuildWithDanglingPinnedRefIsFlaggedStale(@TempDir Path dir) throws Exception {
        String danglingPin = "c".repeat(40);
        // Only the spec file is written; the upstream REQ-0012 does NOT exist in the workspace
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: APPROVED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(danglingPin, danglingPin));

        var graph = new InMemoryTraceabilityGraph();
        var events = new ArrayList<RevalidationRequested>();
        // must not throw
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph, events::add);

        // edge created with the pinned sha as linkedSha
        assertThat(graph.get(ArtifactId.of("SPEC-0007"))).isPresent();
        // upstream absent → current sha is "unknown" → pin != "unknown" → STALE
        assertThat(graph.get(ArtifactId.of("SPEC-0007")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        assertThat(events).extracting(e -> e.subject().value()).containsExactly("SPEC-0007");

        // Test gap 4: edge must exist even though upstream node is absent
        // downstreamOf the dangling upstream id must include the pinning artifact
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0012")))
                .extracting(n -> n.id().value()).contains("SPEC-0007");
    }

    /**
     * workspace/inbox holds raw stakeholder documents (FR-INT-1): no frontmatter, not
     * artifacts. Rebuild must skip them (both pending and processed) instead of crashing —
     * every bootstrap rebuilds from the workspace root BEFORE the inbox is ingested.
     */
    @Test
    void rebuildSkipsRawInboxDocuments(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("inbox/processed"));
        Files.writeString(dir.resolve("inbox/payment-notes.md"), "raw meeting notes, no frontmatter");
        Files.writeString(dir.resolve("inbox/processed/old-notes.md"), "already ingested notes");
        Files.writeString(dir.resolve("REQ-0012.md"), """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """);

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph); // must not throw

        assertThat(graph.get(ArtifactId.of("REQ-0012"))).isPresent();
        assertThat(graph.staleNodes()).isEmpty();
    }

    /**
     * Transitive staleness at rebuild (Defect 2): if REQ-0012 was edited offline and
     * SPEC-0007 pins a stale sha, then STORY-0042 (which derivesFrom SPEC-0007's CURRENT
     * sha) must also be flagged NEEDS_REVALIDATION after rebuild — the spec §5 invariant
     * must hold for chains of any depth.
     */
    @Test
    void rebuildFlagsTransitiveStaleness(@TempDir Path dir) throws Exception {
        String req = """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                EDITED content
                """;
        Files.writeString(dir.resolve("REQ-0012.md"), req);

        // SPEC-0007 pins a stale sha for REQ-0012
        String stalePin = "b".repeat(40);
        String specContent = """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: APPROVED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(stalePin, stalePin);
        Files.writeString(dir.resolve("SPEC-0007.md"), specContent);

        // STORY-0042 pins SPEC-0007's CURRENT sha (it was up-to-date when last generated)
        String specCurrentSha = FrontmatterParser.gitBlobSha(specContent);
        Files.writeString(dir.resolve("STORY-0042.md"), """
                ---
                id: STORY-0042
                type: BacklogItem
                title: Story
                status: APPROVED
                derivesFrom: ['SPEC-0007@%s']
                provenance:
                  sourceRefs: ['SPEC-0007@%s']
                  generatedBy: agent-story@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(specCurrentSha, specCurrentSha));

        var graph = new InMemoryTraceabilityGraph();
        var events = new ArrayList<RevalidationRequested>();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph, events::add);

        // Both SPEC-0007 (directly stale) and STORY-0042 (transitively stale) must be flagged
        assertThat(graph.staleNodes())
                .extracting(n -> n.id().value())
                .containsExactlyInAnyOrder("SPEC-0007", "STORY-0042");
        assertThat(events)
                .extracting(e -> e.subject().value())
                .containsExactlyInAnyOrder("SPEC-0007", "STORY-0042");
    }

    /**
     * Test gap 3: after a clean rebuild (matching pins), a live applyChange must still
     * propagate staleness to direct downstream — steady-state path coverage.
     */
    @Test
    void afterCleanRebuildApplyChangePropagates(@TempDir Path dir) throws Exception {
        String req = """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """;
        Files.writeString(dir.resolve("REQ-0012.md"), req);
        String pin = FrontmatterParser.gitBlobSha(req);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: APPROVED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(pin, pin));

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);
        assertThat(graph.staleNodes()).isEmpty();

        // Now apply a change to REQ-0012 (new sha)
        var events = graph.applyChange(ArtifactId.of("REQ-0012"), "c".repeat(40));

        assertThat(events).extracting(e -> e.subject().value()).contains("SPEC-0007");
        assertThat(graph.staleNodes())
                .extracting(n -> n.id().value()).contains("SPEC-0007");
    }
}
