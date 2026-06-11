package dev.sdlc.adapter.otel;

import dev.sdlc.agent.port.RunTracePort;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NFR-OBS: one span per agent run, a child span per step, token/cost attributes.
 * Wire io.opentelemetry.sdk autoconfigure in bootstrap (OTEL_EXPORTER_OTLP_ENDPOINT);
 * without an endpoint the SDK no-ops, which is the desired fallback.
 */
public final class OtelRunTrace implements RunTracePort {
    private static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("sdlc.run.id");
    private static final AttributeKey<String> STEP_KIND = AttributeKey.stringKey("sdlc.step.kind");
    private static final AttributeKey<Long> IN_TOK = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> OUT_TOK = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Double> COST = AttributeKey.doubleKey("sdlc.cost.usd");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("sdlc.run.outcome");

    private final Tracer tracer;
    private final Map<String, Span> runs = new ConcurrentHashMap<>();

    public OtelRunTrace(OpenTelemetry otel) { this.tracer = otel.getTracer("dev.sdlc.agent"); }

    @Override
    public void step(String runId, String kind, String detail,
                     long inputTokens, long outputTokens, double costUsd) {
        var run = runs.computeIfAbsent(runId, id ->
                tracer.spanBuilder("sdlc.agent.run").setAttribute(RUN_ID, id).startSpan());
        var step = tracer.spanBuilder("sdlc.agent.step." + (kind.startsWith("tool:") ? "tool" : kind))
                .setParent(Context.current().with(run))
                .setAttribute(RUN_ID, runId)
                .setAttribute(STEP_KIND, kind)
                .setAttribute(IN_TOK, inputTokens)
                .setAttribute(OUT_TOK, outputTokens)
                .setAttribute(COST, costUsd)
                .startSpan();
        step.end();
    }

    @Override
    public void finish(String runId, String outcome) {
        var run = runs.remove(runId);
        if (run == null) return;
        run.setAttribute(OUTCOME, outcome);
        run.end();
    }
}
