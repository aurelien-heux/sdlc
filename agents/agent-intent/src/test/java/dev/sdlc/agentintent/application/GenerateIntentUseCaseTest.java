package dev.sdlc.agentintent.application;

import dev.sdlc.agent.*;
import dev.sdlc.agent.port.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.domain.event.SdlcEvent;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GenerateIntentUseCaseTest {
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
    HumanInTheLoopPort human = new HumanInTheLoopPort() {
        public String askClarifyingQuestion(String q) { return "under 5 seconds"; }
        public ApprovalDecision requestApproval(ArtifactId a, String s) {
            return new ApprovalDecision(false, "n/a", null);
        }
    };

    static final String SOURCE = "Meeting notes: we lose carts at checkout. Tax must match the region.";
    static final String MODEL_JSON = """
            {"goals": [{"title": "Faster checkout", "description": "Reduce checkout time",
                        "sourceQuotes": ["we lose carts at checkout"], "assumptions": []}],
             "requirements": [{"title": "Apply regional tax", "description": "Tax by shipping region",
                        "kind": "functional", "moscow": "MUST", "goalTitle": "Faster checkout",
                        "sourceQuotes": ["Tax must match the region"], "assumptions": []}],
             "useCases": []}
            """;

    GenerateIntentUseCase useCase(LanguageModelPort model) {
        return new GenerateIntentUseCase(
                model, human, graph, repo, published::add, noTrace,
                new IntentDraftParser(), "agent-intent@v1", new Guardrails(12, 1.0));
    }

    @Test
    void elicitsWithClarificationAndProducesGroundedArtifacts() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("ask_human", Map.of("question", "how fast must checkout be?")),
                FakeLanguageModel.finalText(MODEL_JSON));

        var ids = useCase(model).generate("inbox/notes.md", SOURCE);

        assertThat(ids).hasSize(2);
        var goalId = ids.stream().filter(i -> i.prefix().equals("GOAL")).findFirst().orElseThrow();
        var reqId = ids.stream().filter(i -> i.prefix().equals("REQ")).findFirst().orElseThrow();

        // nodes PROPOSED with grounded provenance pointing at the source doc
        var goal = graph.get(goalId).orElseThrow();
        assertThat(goal.status()).isEqualTo(NodeStatus.PROPOSED);
        assertThat(goal.provenance().sourceRefs())
                .contains("inbox/notes.md@" + FrontmatterParser.gitBlobSha(SOURCE));
        // requirement derives from the goal
        assertThat(graph.downstreamOf(goalId)).extracting(Node::id).containsExactly(reqId);
        // clarification recorded in the artifact body
        assertThat(files.get(goal.repoPath()))
                .contains("## Clarifications")
                .contains("how fast must checkout be?")
                .contains("under 5 seconds");
        // events per artifact
        assertThat(published).containsExactlyInAnyOrder(
                new ArtifactProposed(goalId), new ArtifactProposed(reqId));
        // round-trip: the written goal file parses and pins the source
        var reparsed = new FrontmatterParser().parse(files.get(goal.repoPath()), goal.repoPath());
        assertThat(reparsed.node().status()).isEqualTo(NodeStatus.PROPOSED);
    }

    @Test
    void nfrKindGetsNfrPrefix() {
        var nfrJson = """
                {"goals": [], "useCases": [],
                 "requirements": [{"title": "Checkout under 5s p95", "description": "latency target",
                        "kind": "nfr", "moscow": "MUST",
                        "sourceQuotes": ["under 5 seconds"], "assumptions": []}]}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(nfrJson));

        var ids = useCase(model).generate("inbox/notes.md", SOURCE);

        assertThat(ids).singleElement().extracting(ArtifactId::prefix).isEqualTo("NFR");
        // pascal()/nodeType() round-trip: literal NFR survives both directions
        var nfr = graph.get(ids.getFirst()).orElseThrow();
        var reparsed = new FrontmatterParser().parse(files.get(nfr.repoPath()), nfr.repoPath());
        assertThat(reparsed.node().type()).isEqualTo(NodeType.NFR);
    }
}
