package dev.sdlc.agentintent.domain;

import java.util.List;

/** requirementTitle links to a RequirementDraft (nullable -> derives from a goal instead). */
public record UseCaseDraft(String title, String actor, List<String> mainFlow,
                           List<String> altFlows, String requirementTitle,
                           List<String> sourceQuotes, List<String> assumptions) {
    public UseCaseDraft {
        mainFlow = List.copyOf(mainFlow);
        altFlows = List.copyOf(altFlows);
        sourceQuotes = List.copyOf(sourceQuotes);
        assumptions = List.copyOf(assumptions);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("use case title required");
        if (actor == null || actor.isBlank())
            throw new IllegalArgumentException("use case actor required");
        if (mainFlow.isEmpty())
            throw new IllegalArgumentException("use case mainFlow required");
        if (sourceQuotes.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("draft must be grounded: sourceQuotes or assumptions");
    }
}
