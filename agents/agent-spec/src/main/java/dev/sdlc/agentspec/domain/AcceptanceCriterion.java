package dev.sdlc.agentspec.domain;

/** One Gherkin scenario: a name plus Given/When/Then steps. */
public record AcceptanceCriterion(String scenario, String steps) {
    public AcceptanceCriterion {
        if (scenario == null || scenario.isBlank())
            throw new IllegalArgumentException("scenario name required");
        if (steps == null || !steps.contains("Given") || !steps.contains("When") || !steps.contains("Then"))
            throw new IllegalArgumentException("steps must contain Given/When/Then");
    }
}
