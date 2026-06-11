package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral contract every TraceabilityGraphPort adapter must satisfy.
 * Subclasses provide the adapter (in-memory, postgres, ...).
 */
public abstract class TraceabilityGraphContract {
    protected static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");
    protected static final Provenance PROV = Provenance.generated(List.of("doc:x"), "test", 1.0, List.of());

    protected TraceabilityGraphPort graph;

    protected abstract TraceabilityGraphPort newGraph();

    protected Node node(String id, NodeType type, NodeStatus status, String sha) {
        var prov = status == NodeStatus.APPROVED ? PROV.approve("tester", T0) : PROV;
        return new Node(ArtifactId.of(id), type, id, id + ".md", sha, status, 1, prov, T0, T0);
    }

    @BeforeEach
    void chain() { // GOAL-0001 <- REQ-0012 <- SPEC-0007 <- STORY-0042
        graph = newGraph();
        graph.upsert(node("GOAL-0001", NodeType.GOAL, NodeStatus.APPROVED, "g1"));
        graph.upsert(node("REQ-0012", NodeType.REQUIREMENT, NodeStatus.APPROVED, "r1"));
        graph.upsert(node("SPEC-0007", NodeType.SPECIFICATION, NodeStatus.APPROVED, "s1"));
        graph.upsert(node("STORY-0042", NodeType.BACKLOG_ITEM, NodeStatus.APPROVED, "b1"));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("REQ-0012"), ArtifactId.of("GOAL-0001"), "g1", "test", T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("SPEC-0007"), ArtifactId.of("REQ-0012"), "r1", "test", T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("STORY-0042"), ArtifactId.of("SPEC-0007"), "s1", "test", T0));
    }

    @Test
    void upstreamChangeMarksDirectDependentStaleAndRecursesTransitively() {
        var events = graph.applyChange(ArtifactId.of("REQ-0012"), "r2");

        // direct: SPEC-0007 needs revalidation, its edge is STALE
        assertThat(graph.get(ArtifactId.of("SPEC-0007")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        // transitive: STORY-0042 flagged too (flag only — its edge sha untouched)
        assertThat(graph.get(ArtifactId.of("STORY-0042")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        // upstream of the change is untouched
        assertThat(graph.get(ArtifactId.of("GOAL-0001")).orElseThrow().status())
                .isEqualTo(NodeStatus.APPROVED);
        // events: one RevalidationRequested per flagged node
        assertThat(events).extracting(e -> e.subject().value())
                .containsExactlyInAnyOrder("SPEC-0007", "STORY-0042");
        // changed node has new sha and bumped version
        var req = graph.get(ArtifactId.of("REQ-0012")).orElseThrow();
        assertThat(req.blobSha()).isEqualTo("r2");
        assertThat(req.version()).isEqualTo(2);
    }

    @Test
    void impactOfAnswersTheHeadlineQuery() {
        assertThat(graph.impactOf(ArtifactId.of("REQ-0012")))
                .extracting(n -> n.id().value())
                .containsExactlyInAnyOrder("SPEC-0007", "STORY-0042");
    }

    @Test
    void revalidationRestampsEdgeToCurrent() {
        graph.applyChange(ArtifactId.of("REQ-0012"), "r2");
        var staleEdgeId = "SPEC-0007->REQ-0012:DERIVES_FROM";
        graph.revalidate(staleEdgeId, "a.dupont");
        assertThat(graph.staleNodes()).extracting(n -> n.id().value())
                .doesNotContain("SPEC-0007");
    }

    @Test
    void revalidatingEdgeOfFormerlyProposedNodeDemotesToDraftInsteadOfCrashing() {
        // SPEC was PROPOSED (not human-approved) when flagged; revalidation must not promote it
        graph.upsert(node("SPEC-0007", NodeType.SPECIFICATION, NodeStatus.PROPOSED, "s1"));
        graph.applyChange(ArtifactId.of("REQ-0012"), "r2");
        graph.revalidate("SPEC-0007->REQ-0012:DERIVES_FROM", "a.dupont");
        assertThat(graph.get(ArtifactId.of("SPEC-0007")).orElseThrow().status())
                .isEqualTo(NodeStatus.DRAFT);
    }

    @Test
    void revalidationClearsTransitivelyFlaggedDependentsWithNoStaleEdges() {
        graph.applyChange(ArtifactId.of("REQ-0012"), "r2");
        // STORY-0042 was flagged transitively; its own edge to SPEC-0007 is still CURRENT
        graph.revalidate("SPEC-0007->REQ-0012:DERIVES_FROM", "a.dupont");
        assertThat(graph.staleNodes()).isEmpty(); // both SPEC and STORY cleared
    }

    @Test
    void applyChangeWithUnchangedShaIsAFullNoOp() {
        graph.applyChange(ArtifactId.of("REQ-0012"), "r2");
        var versionAfterFirst = graph.get(ArtifactId.of("REQ-0012")).orElseThrow().version();
        var events = graph.applyChange(ArtifactId.of("REQ-0012"), "r2"); // redelivery
        assertThat(events).isEmpty();
        assertThat(graph.get(ArtifactId.of("REQ-0012")).orElseThrow().version())
                .isEqualTo(versionAfterFirst);
    }

    @Test
    void impactTraversalStopsAtDanglingIntermediateNodes() {
        // BB's node is missing (edge exists via direct link()); traversal must not pass through it
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("BB-0001"),
                ArtifactId.of("GOAL-0001"), "g1", "test", T0));
        graph.upsert(node("CC-0001", NodeType.REQUIREMENT, NodeStatus.DRAFT, "c1"));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("CC-0001"),
                ArtifactId.of("BB-0001"), "unknown", "test", T0));

        assertThat(graph.impactOf(ArtifactId.of("GOAL-0001")))
                .extracting(n -> n.id().value())
                .containsExactlyInAnyOrder("REQ-0012", "SPEC-0007", "STORY-0042"); // not CC-0001
    }

    @Test
    void impactTerminatesOnCycles() {
        graph.upsert(node("FF-0001", NodeType.REQUIREMENT, NodeStatus.DRAFT, "f1"));
        graph.upsert(node("GG-0001", NodeType.REQUIREMENT, NodeStatus.DRAFT, "g1"));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("FF-0001"), ArtifactId.of("GG-0001"), "g1", "test", T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("GG-0001"), ArtifactId.of("FF-0001"), "f1", "test", T0));

        assertThat(graph.impactOf(ArtifactId.of("FF-0001")))
                .extracting(n -> n.id().value())
                .containsExactlyInAnyOrder("FF-0001", "GG-0001"); // terminates; changed node included when on a cycle
    }

    @Test
    void createdAtFollowsTheUpsertedNode() {
        var original = graph.get(ArtifactId.of("REQ-0012")).orElseThrow();
        var later = new Node(original.id(), original.type(), original.title(), original.repoPath(),
                original.blobSha(), original.status(), original.version(), original.provenance(),
                T0.plusSeconds(3600), T0.plusSeconds(3600));
        graph.upsert(later);
        assertThat(graph.get(ArtifactId.of("REQ-0012")).orElseThrow().createdAt())
                .isEqualTo(T0.plusSeconds(3600)); // upsert replaces the whole node (in-memory reference semantics)
    }
}
