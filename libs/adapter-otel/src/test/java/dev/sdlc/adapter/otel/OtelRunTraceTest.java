package dev.sdlc.adapter.otel;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtelRunTraceTest {
    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @Test
    void runBecomesSpanWithStepsAndCounters() {
        var trace = new OtelRunTrace(OTEL.getOpenTelemetry());
        trace.step("run-1", "model", "tool-request", 100, 20, 0.0015);
        trace.step("run-1", "tool:echo", "ok", 0, 0, 0);
        trace.finish("run-1", "completed");

        List<SpanData> spans = OTEL.getSpans();
        // OpenTelemetryExtension reports only ENDED spans.
        // Step spans end immediately; run span ends on finish().
        // Total: 1 run span + 2 step spans = 3 spans.
        assertThat(spans).hasSize(3);

        SpanData run = spans.stream()
                .filter(s -> s.getName().equals("sdlc.agent.run"))
                .findFirst().orElseThrow();
        // outcome attribute set before end()
        assertThat(run.getAttributes().asMap().toString()).contains("completed");

        SpanData modelStep = spans.stream()
                .filter(s -> s.getName().equals("sdlc.agent.step.model"))
                .findFirst().orElseThrow();
        assertThat(modelStep.getAttributes().asMap().toString())
                .contains("100").contains("20");

        SpanData toolStep = spans.stream()
                .filter(s -> s.getName().equals("sdlc.agent.step.tool"))
                .findFirst().orElseThrow();

        // Parent-child linkage: step spans' parentSpanId must equal the run span's spanId
        String runSpanId = run.getSpanId();
        assertThat(runSpanId).isNotEqualTo(SpanId.getInvalid());
        assertThat(modelStep.getParentSpanId()).isEqualTo(runSpanId);
        assertThat(toolStep.getParentSpanId()).isEqualTo(runSpanId);
    }

    @Test
    void finishOnUnknownRunIsANoOp() {
        var trace = new OtelRunTrace(OTEL.getOpenTelemetry());
        trace.finish("never-started", "completed"); // must not throw
    }
}
