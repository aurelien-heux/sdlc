package dev.sdlc.agent;

/** Hard limits per run (brief §5 step 5). */
public record Guardrails(int maxIterations, double costCeilingUsd) {}
