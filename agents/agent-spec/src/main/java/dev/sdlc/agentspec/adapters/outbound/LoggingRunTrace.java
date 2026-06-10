package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.agent.port.RunTracePort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** NFR-OBS Phase 0: per-run token/cost counters, printed at finish. OTel exporter in Phase 1. */
public final class LoggingRunTrace implements RunTracePort {
    private record Counters(LongAdder tokens, DoubleAdder cost) {}
    private final Map<String, Counters> runs = new ConcurrentHashMap<>();

    @Override public void step(String runId, String kind, String detail,
                               long inputTokens, long outputTokens, double costUsd) {
        var c = runs.computeIfAbsent(runId, r -> new Counters(new LongAdder(), new DoubleAdder()));
        c.tokens().add(inputTokens + outputTokens);
        c.cost().add(costUsd);
        System.out.printf("[trace %s] %s in=%d out=%d cost=$%.4f%n",
                runId, kind, inputTokens, outputTokens, costUsd);
    }

    @Override public void finish(String runId, String outcome) {
        var c = runs.getOrDefault(runId, new Counters(new LongAdder(), new DoubleAdder()));
        System.out.printf("[trace %s] %s — total tokens=%d cost=$%.4f%n",
                runId, outcome, c.tokens().sum(), c.cost().doubleValue());
    }
}
