package dev.sdlc.agent;

public record AgentRunResult(String finalText, int iterations, long totalTokens, double totalCostUsd) {}
