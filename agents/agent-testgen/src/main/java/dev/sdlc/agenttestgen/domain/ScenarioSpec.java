package dev.sdlc.agenttestgen.domain;

/** One Gherkin scenario lifted verbatim from a spec's acceptance criteria. */
public record ScenarioSpec(String name, String steps) {
    public ScenarioSpec {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("scenario name required");
        if (steps == null || !steps.contains("Given") || !steps.contains("When") || !steps.contains("Then"))
            throw new IllegalArgumentException("steps must contain Given/When/Then");
    }
}
