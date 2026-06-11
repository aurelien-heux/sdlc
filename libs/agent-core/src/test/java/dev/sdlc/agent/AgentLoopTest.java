package dev.sdlc.agent;

import dev.sdlc.agent.port.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicReference;

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

    static final class RecordingTrace implements RunTracePort {
        final List<String> steps = new java.util.ArrayList<>();
        String outcome;
        public void step(String r, String kind, String d, long i, long o, double c) { steps.add(kind); }
        public void finish(String r, String outcome) { this.outcome = outcome; }
    }

    @Test
    void finalAnswerIsReturnedEvenIfItTipsCostOverCeiling() {
        // the money is already spent; aborting would discard a paid-for answer
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "1")),   // cost 0.001
                FakeLanguageModel.finalText("expensive but done"));         // cost 0.001 → over 0.0015
        var trace = new RecordingTrace();
        var loop = new AgentLoop(model, registry, trace, new Guardrails(5, 0.0015));

        var result = loop.run("run-1", "system", "task");

        assertThat(result.finalText()).isEqualTo("expensive but done");
        assertThat(trace.outcome).isEqualTo("completed");
    }

    @Test
    void costCeilingAbortClosesTheTrace() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "1")),
                FakeLanguageModel.toolCall("echo", Map.of("text", "2")));
        var trace = new RecordingTrace();
        var loop = new AgentLoop(model, registry, trace, new Guardrails(5, 0.0015));

        assertThatThrownBy(() -> loop.run("run-1", "system", "task"))
                .isInstanceOf(AgentLoop.GuardrailExceeded.class);
        assertThat(trace.outcome).isEqualTo("aborted:cost");
    }

    @Test
    void toolFailureClosesTheTraceBeforePropagating() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("not-registered", Map.of()));
        var trace = new RecordingTrace();
        var loop = new AgentLoop(model, registry, trace, new Guardrails(5, 1.0));

        assertThatThrownBy(() -> loop.run("run-1", "system", "task"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(trace.outcome).isEqualTo("aborted:tool-error");
    }

    @Test
    void runContextIsScopedDuringTheRun() {
        var seen = new AtomicReference<String>();
        Tool peek = new Tool() {
            public String name() { return "peek"; }
            public String description() { return "peek"; }
            public Map<String, String> parameterSchema() { return Map.of(); }
            public String execute(Map<String, Object> args) {
                seen.set(RunContext.current().map(RunContext::runId).orElse(null));
                return "ok";
            }
        };
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("peek", Map.of()),
                FakeLanguageModel.finalText("done"));
        var loop = new AgentLoop(model, new ToolRegistry(List.of(peek)), noTrace, new Guardrails(5, 1.0));

        loop.run("run-ctx-1", "system", "task");

        assertThat(seen.get()).isEqualTo("run-ctx-1");
        assertThat(RunContext.current()).isEmpty(); // not leaked outside the run
    }
}
