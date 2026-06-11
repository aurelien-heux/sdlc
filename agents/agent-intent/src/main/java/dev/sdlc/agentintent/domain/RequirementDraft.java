package dev.sdlc.agentintent.domain;

import java.util.List;
import java.util.Set;

/** kind: functional|nfr; moscow: MUST|SHOULD|COULD|WONT; goalTitle links to a GoalDraft (nullable). */
public record RequirementDraft(String title, String description, String kind, String moscow,
                               String goalTitle, List<String> sourceQuotes, List<String> assumptions) {
    private static final Set<String> KINDS = Set.of("functional", "nfr");
    private static final Set<String> MOSCOW = Set.of("MUST", "SHOULD", "COULD", "WONT");

    public RequirementDraft {
        sourceQuotes = List.copyOf(sourceQuotes);
        assumptions = List.copyOf(assumptions);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("requirement title required");
        if (!KINDS.contains(kind))
            throw new IllegalArgumentException("kind must be functional|nfr: " + kind);
        if (!MOSCOW.contains(moscow))
            throw new IllegalArgumentException("moscow must be MUST|SHOULD|COULD|WONT: " + moscow);
        if (sourceQuotes.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("draft must be grounded: sourceQuotes or assumptions");
    }
}
