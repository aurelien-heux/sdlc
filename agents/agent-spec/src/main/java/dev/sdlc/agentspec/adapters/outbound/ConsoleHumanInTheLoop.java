package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.domain.ArtifactId;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Phase 0 HITL: stdin. Phase 1 replaces this with PR review / chat without touching the app. */
public final class ConsoleHumanInTheLoop implements HumanInTheLoopPort {
    private final BufferedReader in;
    private final String reviewer;

    public ConsoleHumanInTheLoop(BufferedReader in, String reviewer) {
        this.in = in; this.reviewer = reviewer;
    }

    @Override public String askClarifyingQuestion(String question) {
        System.out.println("\n[clarification needed] " + question);
        System.out.print("> ");
        return readLine();
    }

    @Override public ApprovalDecision requestApproval(ArtifactId artifact, String summary) {
        System.out.println("\n[approval requested] " + artifact.value() + " — " + summary);
        System.out.print("approve? (y/n + optional feedback): ");
        String line = readLine();
        boolean approved = line.trim().toLowerCase().startsWith("y");
        String feedback = line.trim().length() > 1 ? line.trim().substring(1).trim() : null;
        return new ApprovalDecision(approved, reviewer, feedback);
    }

    private String readLine() {
        try {
            String line = in.readLine();
            return line == null ? "" : line;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
