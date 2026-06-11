package dev.sdlc.agentintent.application;

import dev.sdlc.agent.FakeLanguageModel;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.RunTracePort;
import dev.sdlc.domain.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewFlagsUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    Node node(String id, NodeType type, String title) {
        var prov = Provenance.generated(List.of("x"), "h", 1.0, List.of()).approve("a", T0);
        return new Node(ArtifactId.of(id), type, title, id + ".md", "s1",
                NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    @Test
    void recordsDuplicateAndConflictEdgesFromModelVerdict() {
        graph.upsert(node("REQ-0001", NodeType.REQUIREMENT, "Apply regional tax"));
        graph.upsert(node("REQ-0002", NodeType.REQUIREMENT, "Apply regional tax at checkout"));
        var verdict = """
                {"flags": [{"newId": "REQ-0002", "existingId": "REQ-0001",
                            "relation": "DUPLICATES", "reason": "same tax requirement"}]}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(verdict));

        var flags = new ReviewFlagsUseCase(model, graph, noTrace, new Guardrails(3, 0.5), "agent-intent@v1")
                .reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

        assertThat(flags).singleElement().satisfies(f -> {
            assertThat(f.relation()).isEqualTo(EdgeType.DUPLICATES);
            assertThat(f.reason()).contains("same tax");
        });
        // edge recorded in the graph
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0001"), EdgeType.DUPLICATES))
                .extracting(n -> n.id().value()).containsExactly("REQ-0002");
    }

    @Test
    void noExistingArtifactsMeansNoModelCallAndNoFlags() {
        graph.upsert(node("REQ-0002", NodeType.REQUIREMENT, "anything"));
        var model = new FakeLanguageModel(); // would throw if called

        var flags = new ReviewFlagsUseCase(model, graph, noTrace, new Guardrails(3, 0.5), "agent-intent@v1")
                .reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

        assertThat(flags).isEmpty();
        assertThat(model.requests).isEmpty();
    }
}
