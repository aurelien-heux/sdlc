package dev.sdlc.agentdesign.application;

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

class GenerateDesignUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String SPEC_SHA = "c".repeat(40);

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

    Node approvedSpec() {
        var prov = Provenance.generated(List.of("REQ-0001@" + "a".repeat(40)), "agent-spec@v1",
                0.8, List.of()).approve("a.dupont", T0);
        return new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "Checkout tax",
                "specs/SPEC-0001.md", SPEC_SHA, NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    static final String MODEL_JSON = """
            {"elements": [{"title": "TaxCalculator", "description": "Pure domain service"}],
             "adrs": [{"title": "Outbox pattern", "context": "c", "decision": "outbox",
                       "alternatives": [{"option": "direct", "tradeoff": "lossy"},
                                        {"option": "CDC", "tradeoff": "heavy"}],
                       "consequences": ["relay"]}],
             "apiContracts": []}
            """;

    @Test
    void producesProposedDesignArtifactsDerivingFromTheSpec() {
        graph.upsert(approvedSpec());
        files.put("specs/SPEC-0001.md", "---\nid: SPEC-0001\n---\nspec body text");
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(MODEL_JSON));

        var ids = new GenerateDesignUseCase(model, graph, repo, published::add, noTrace,
                new DesignDraftParser(), "agent-design@v1", new Guardrails(5, 1.0))
                .generate(ArtifactId.of("SPEC-0001"));

        assertThat(ids).hasSize(2);
        var adrId = ids.stream().filter(i -> i.prefix().equals("ADR")).findFirst().orElseThrow();
        var adr = graph.get(adrId).orElseThrow();
        assertThat(adr.status()).isEqualTo(NodeStatus.PROPOSED);
        // derives from the spec, pinned
        assertThat(files.get(adr.repoPath())).contains("SPEC-0001@" + SPEC_SHA);
        // alternatives rendered
        assertThat(files.get(adr.repoPath())).contains("## Alternatives").contains("CDC");
        // graph-scope assumption recorded
        assertThat(adr.provenance().assumptions())
                .anyMatch(a -> a.contains("graph summaries"));
        // prompt contained the spec body
        assertThat(model.requests.getFirst().messages().getFirst().content())
                .contains("spec body text");
        assertThat(published).contains(new ArtifactProposed(adrId));
        // round-trip
        var reparsed = new FrontmatterParser().parse(files.get(adr.repoPath()), adr.repoPath());
        assertThat(reparsed.edgeTargets().get(EdgeType.DERIVES_FROM))
                .containsExactly(new EdgeTarget(ArtifactId.of("SPEC-0001"), SPEC_SHA));
    }

    @Test
    void refusesUnapprovedSpec() {
        var prov = Provenance.generated(List.of("x"), "h", 1.0, List.of());
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "t",
                "specs/SPEC-0001.md", SPEC_SHA, NodeStatus.PROPOSED, 1, prov, T0, T0));
        var model = new FakeLanguageModel();

        assertThatThrownBy(() -> new GenerateDesignUseCase(model, graph, repo, published::add,
                noTrace, new DesignDraftParser(), "agent-design@v1", new Guardrails(5, 1.0))
                .generate(ArtifactId.of("SPEC-0001")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty();
    }
}
