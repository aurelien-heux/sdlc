package dev.sdlc.agentintent.domain;

import java.util.List;

/** A goal the model extracted; grounded in verbatim quotes from the source or explicit assumptions. */
public record GoalDraft(String title, String description,
                        List<String> sourceQuotes, List<String> assumptions) {
    public GoalDraft {
        sourceQuotes = List.copyOf(sourceQuotes);
        assumptions = List.copyOf(assumptions);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("goal title required");
        if (sourceQuotes.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("draft must be grounded: sourceQuotes or assumptions");
    }
}
