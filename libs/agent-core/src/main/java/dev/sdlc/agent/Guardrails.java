package dev.sdlc.agent;

/** Hard limits per run (brief §5 step 5). */
public record Guardrails(int maxIterations, double costCeilingUsd) {
    public Guardrails {
        if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be >= 1");
        if (!(costCeilingUsd > 0)) throw new IllegalArgumentException("costCeilingUsd must be > 0");
    }
}
