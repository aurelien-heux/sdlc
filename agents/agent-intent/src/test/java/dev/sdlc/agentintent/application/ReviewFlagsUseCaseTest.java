package dev.sdlc.agentintent.application;

import dev.sdlc.agent.FakeLanguageModel;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.LanguageModelPort;
import dev.sdlc.agent.port.RunTracePort;
import dev.sdlc.domain.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewFlagsUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
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

    Node node(String id, NodeType type, String title) {
        var prov = Provenance.generated(List.of("x"), "h", 1.0, List.of()).approve("a", T0);
        return new Node(ArtifactId.of(id), type, title, id + ".md", "s1",
                NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    ReviewFlagsUseCase reviewUseCase(LanguageModelPort model) {
        return new ReviewFlagsUseCase(model, graph, repo, noTrace,
                new Guardrails(3, 0.5), "agent-intent@v1");
    }

    @Test
    void recordsDuplicateAndConflictEdgesFromModelVerdict() {
        graph.upsert(node("REQ-0001", NodeType.REQUIREMENT, "Apply regional tax"));
        graph.upsert(node("REQ-0002", NodeType.REQUIREMENT, "Apply regional tax at checkout"));
        files.put("REQ-0002.md", "body\n");
        var verdict = """
                {"flags": [{"newId": "REQ-0002", "existingId": "REQ-0001",
                            "relation": "DUPLICATES", "reason": "same tax requirement"}]}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(verdict));

        var flags = reviewUseCase(model).reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

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

        var flags = reviewUseCase(model).reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

        assertThat(flags).isEmpty();
        assertThat(model.requests).isEmpty();
    }

    @Test
    void flagsAreSurfacedInTheArtifactFile() {
        graph.upsert(node("REQ-0001", NodeType.REQUIREMENT, "Apply regional tax"));
        graph.upsert(node("REQ-0002", NodeType.REQUIREMENT, "Apply regional tax at checkout"));
        files.put("REQ-0002.md", "---\nid: REQ-0002\n---\nTax by shipping region\n");
        var verdict = """
                {"flags": [{"newId": "REQ-0002", "existingId": "REQ-0001",
                            "relation": "DUPLICATES", "reason": "same tax requirement"}]}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(verdict));

        reviewUseCase(model).reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

        assertThat(files.get(graph.get(ArtifactId.of("REQ-0002")).orElseThrow().repoPath()))
                .contains("## Review flags")
                .contains("DUPLICATES REQ-0001")
                .contains("same tax requirement");
        // node sha updated to the rewritten file
        assertThat(graph.get(ArtifactId.of("REQ-0002")).orElseThrow().blobSha())
                .isEqualTo(FrontmatterParser.gitBlobSha(files.get(
                        graph.get(ArtifactId.of("REQ-0002")).orElseThrow().repoPath())));
    }

    @Test
    void advisoryPassFailureIsNonFatal() {
        graph.upsert(node("REQ-0001", NodeType.REQUIREMENT, "existing"));
        graph.upsert(node("REQ-0002", NodeType.REQUIREMENT, "new one"));
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText("sorry, prose"));

        var flags = reviewUseCase(model).reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

        assertThat(flags).isEmpty(); // degraded, not dead
    }
}
