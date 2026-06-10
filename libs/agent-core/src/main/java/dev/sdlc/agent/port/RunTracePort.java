package dev.sdlc.agent.port;

/** NFR-OBS: every step recorded with tokens and cost. OTel export arrives in Phase 1. */
public interface RunTracePort {
    void step(String runId, String kind, String detail, long inputTokens, long outputTokens, double costUsd);
    void finish(String runId, String outcome);
}
