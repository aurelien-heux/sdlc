package dev.sdlc.agentbacklog.application;

import dev.sdlc.adapter.common.FileBacklogAdapter;
import dev.sdlc.agent.*;
import dev.sdlc.agent.port.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.domain.event.SdlcEvent;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GenerateBacklogUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String SPEC_SHA = "c".repeat(40);
    static final String ADR_SHA = "d".repeat(40);

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    List<SdlcEvent> published = new ArrayList<>();
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

    Node approved(String id, NodeType type, String sha) {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of()).approve("a.dupont", T0);
        return new Node(ArtifactId.of(id), type, id, id + ".md", sha,
                NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    static final String MODEL_JSON = """
            {"items": [
              {"level": "epic", "title": "Checkout tax", "description": "all tax work",
               "acceptanceHook": null, "estimate": "L", "dependsOn": []},
              {"level": "story", "title": "Regional rate lookup", "description": "lookup by region",
               "acceptanceHook": "FR VAT", "estimate": "M", "dependsOn": ["Checkout tax"]}
            ]}
            """;

    @Test
    void producesBacklogItemsWithDependencyEdgesAndFiles() {
        graph.upsert(approved("SPEC-0001", NodeType.SPECIFICATION, SPEC_SHA));
        graph.upsert(approved("ADR-0001", NodeType.ADR, ADR_SHA));
        files.put("SPEC-0001.md", "---\nid: SPEC-0001\n---\nGherkin here");
        files.put("ADR-0001.md", "---\nid: ADR-0001\n---\noutbox decision");
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(MODEL_JSON));
        var backlog = new FileBacklogAdapter(repo);

        var ids = new GenerateBacklogUseCase(model, graph, repo, backlog, published::add, noTrace,
                new BacklogDraftParser(), "agent-backlog@v1", new Guardrails(5, 1.0))
                .generate(ArtifactId.of("SPEC-0001"), List.of(ArtifactId.of("ADR-0001")));

        assertThat(ids).hasSize(2);
        var epicId = ids.getFirst();   // EPIC-0001
        var storyId = ids.get(1);      // STORY-0001
        assertThat(epicId.prefix()).isEqualTo("EPIC");
        assertThat(storyId.prefix()).isEqualTo("STORY");
        // both derive from the spec (pinned) and the design artifact
        var storyFile = files.get(graph.get(storyId).orElseThrow().repoPath());
        assertThat(storyFile).contains("SPEC-0001@" + SPEC_SHA).contains("ADR-0001@" + ADR_SHA);
        // dependency edge story -> epic
        assertThat(graph.downstreamOf(epicId, EdgeType.DEPENDS_ON))
                .extracting(Node::id).containsExactly(storyId);
        // estimate + level + acceptance hook in frontmatter-adjacent body
        assertThat(storyFile).contains("level: story").contains("estimate: M").contains("FR VAT");
        assertThat(published).contains(new ArtifactProposed(epicId), new ArtifactProposed(storyId));
    }

    @Test
    void refusesUnapprovedInputs() {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of());
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "t", "p",
                SPEC_SHA, NodeStatus.PROPOSED, 1, prov, T0, T0));
        var model = new FakeLanguageModel();
        var backlog = new FileBacklogAdapter(repo);

        assertThatThrownBy(() -> new GenerateBacklogUseCase(model, graph, repo, backlog,
                published::add, noTrace, new BacklogDraftParser(), "agent-backlog@v1",
                new Guardrails(5, 1.0)).generate(ArtifactId.of("SPEC-0001"), List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty();
    }
}
