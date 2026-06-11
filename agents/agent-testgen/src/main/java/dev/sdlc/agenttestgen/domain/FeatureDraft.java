package dev.sdlc.agenttestgen.domain;

import java.util.List;

public record FeatureDraft(String title, List<ScenarioSpec> scenarios) {
    public FeatureDraft {
        scenarios = List.copyOf(scenarios);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("feature title required");
        if (scenarios.isEmpty())
            throw new IllegalArgumentException("feature needs at least one scenario");
    }
}
