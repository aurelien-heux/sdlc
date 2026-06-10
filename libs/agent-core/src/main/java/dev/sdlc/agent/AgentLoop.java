package dev.sdlc.agent;

import dev.sdlc.agent.port.*;
import dev.sdlc.agent.port.LanguageModelPort.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The reasoning loop (brief §5): assemble context → call model → execute requested
 * tools through the registry → feed results back → repeat until final text.
 * Pure application code: no SDK, no HTTP, no framework.
 */
public final class AgentLoop {
    private final LanguageModelPort model;
    private final ToolRegistry tools;
    private final RunTracePort trace;
    private final Guardrails guardrails;

    public AgentLoop(LanguageModelPort model, ToolRegistry tools, RunTracePort trace, Guardrails guardrails) {
        this.model = model; this.tools = tools; this.trace = trace; this.guardrails = guardrails;
    }

    public AgentRunResult run(String runId, String systemPrompt, String task) {
        var messages = new ArrayList<Message>();
        messages.add(new Message(Role.USER, task));
        long tokens = 0; double cost = 0;

        for (int i = 1; i <= guardrails.maxIterations(); i++) {
            var response = model.complete(new ModelRequest(systemPrompt, List.copyOf(messages), tools.schemas()));
            tokens += response.usage().inputTokens() + response.usage().outputTokens();
            cost += response.usage().costUsd();
            trace.step(runId, "model", response.wantsTools() ? "tool-request" : "final",
                    response.usage().inputTokens(), response.usage().outputTokens(),
                    response.usage().costUsd());
            if (cost > guardrails.costCeilingUsd()) {
                trace.finish(runId, "aborted:cost");
                throw new GuardrailExceeded("cost ceiling exceeded: $" + cost);
            }
            if (!response.wantsTools()) {
                trace.finish(runId, "completed");
                return new AgentRunResult(response.finalText(), i, tokens, cost);
            }
            for (var call : response.toolCalls()) {
                String result = tools.execute(call.toolName(), call.arguments());
                trace.step(runId, "tool:" + call.toolName(), result, 0, 0, 0);
                messages.add(new Message(Role.TOOL_RESULT,
                        "[" + call.toolName() + "] " + result));
            }
        }
        trace.finish(runId, "aborted:iterations");
        throw new GuardrailExceeded("max iterations reached: " + guardrails.maxIterations());
    }

    public static final class GuardrailExceeded extends RuntimeException {
        public GuardrailExceeded(String message) { super(message); }
    }
}
