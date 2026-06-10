package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChangePropagationTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");
    static final Provenance PROV = Provenance.generated(List.of("doc:x"), "test", 1.0, List.of());

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();

    Node node(String id, NodeType type, NodeStatus status, String sha) {
        var prov = status == NodeStatus.APPROVED ? PROV.approve("tester", T0) : PROV;
        return new Node(ArtifactId.of(id), type, id, id + ".md", sha, status, 1, prov, T0, T0);
    }

    @BeforeEach
    void chain() { // GOAL-0001 <- REQ-0012 <- SPEC-0007 <- STORY-0042
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
}
