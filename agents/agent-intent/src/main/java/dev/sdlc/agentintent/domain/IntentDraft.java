package dev.sdlc.agentintent.domain;

import java.util.List;

/** Everything the Intent agent extracted from one source document. */
public record IntentDraft(List<GoalDraft> goals, List<RequirementDraft> requirements,
                          List<UseCaseDraft> useCases) {
    public IntentDraft {
        goals = List.copyOf(goals);
        requirements = List.copyOf(requirements);
        useCases = List.copyOf(useCases);
        if (goals.isEmpty() && requirements.isEmpty() && useCases.isEmpty())
            throw new IllegalArgumentException("intent draft needs at least one item");
    }
}
