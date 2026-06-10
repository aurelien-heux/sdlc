package dev.sdlc.agentspec.application;

import dev.sdlc.domain.*;
import dev.sdlc.domain.event.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ArtifactChangedHandlerTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");

    @Test
    void changeEventStalesDownstreamAndEmitsRevalidationRequests() {
        var graph = new InMemoryTraceabilityGraph();
        var prov = Provenance.generated(List.of("x"), "h", 1.0, List.of()).approve("a.dupont", T0);
        graph.upsert(new Node(ArtifactId.of("REQ-0012"), NodeType.REQUIREMENT, "r",
                "requirements/REQ-0012.md", "r1", NodeStatus.APPROVED, 1, prov, T0, T0));
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "s",
                "specs/SPEC-0001.md", "s1", NodeStatus.APPROVED, 1, prov, T0, T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("SPEC-0001"),
                ArtifactId.of("REQ-0012"), "r1", "test", T0));
        List<SdlcEvent> published = new ArrayList<>();

        var handler = new ArtifactChangedHandler(graph, published::add);
        handler.on(new ArtifactChanged(ArtifactId.of("REQ-0012"), "r2"));

        assertThat(graph.get(ArtifactId.of("SPEC-0001")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        assertThat(published).singleElement().isInstanceOf(RevalidationRequested.class);
        // idempotent: redelivery emits nothing new (NFR-RELY)
        handler.on(new ArtifactChanged(ArtifactId.of("REQ-0012"), "r2"));
        assertThat(published).hasSize(1);
    }
}
