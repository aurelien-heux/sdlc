package dev.sdlc.agent;

import dev.sdlc.agent.port.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AgentLoopTest {
    Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "echo"; }
        public Map<String, String> parameterSchema() { return Map.of("text", "string"); }
        public String execute(Map<String, Object> args) { return "tool says: " + args.get("text"); }
    };
    ToolRegistry registry = new ToolRegistry(List.of(echo));
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    @Test
    void returnsFinalTextWhenModelFinishesImmediately() {
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText("done"));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(5, 1.00));

        var result = loop.run("run-1", "system", "task");

        assertThat(result.finalText()).isEqualTo("done");
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    void executesToolCallsAndFeedsResultsBack() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "hi")),
                FakeLanguageModel.finalText("finished"));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(5, 1.00));

        var result = loop.run("run-1", "system", "task");

        assertThat(result.finalText()).isEqualTo("finished");
        // second request must contain the tool result message
        var second = model.requests.get(1);
        assertThat(second.messages())
                .anyMatch(m -> m.role() == LanguageModelPort.Role.TOOL_RESULT
                        && m.content().contains("tool says: hi"));
    }

    @Test
    void stopsAtMaxIterations() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "1")),
                FakeLanguageModel.toolCall("echo", Map.of("text", "2")),
                FakeLanguageModel.toolCall("echo", Map.of("text", "3")));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(2, 1.00));

        assertThatThrownBy(() -> loop.run("run-1", "system", "task"))
                .isInstanceOf(AgentLoop.GuardrailExceeded.class)
                .hasMessageContaining("iterations");
    }

    @Test
    void stopsAtCostCeiling() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "1")),
                FakeLanguageModel.finalText("never reached"));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(5, 0.0005));

        assertThatThrownBy(() -> loop.run("run-1", "system", "task"))
                .isInstanceOf(AgentLoop.GuardrailExceeded.class)
                .hasMessageContaining("cost");
    }
}
