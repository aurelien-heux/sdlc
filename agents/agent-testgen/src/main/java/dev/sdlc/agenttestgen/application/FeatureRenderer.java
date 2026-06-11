package dev.sdlc.agenttestgen.application;

import dev.sdlc.agenttestgen.domain.FeatureDraft;

import java.util.stream.Collectors;

/** FeatureDraft -> Cucumber feature text. Pure string assembly, zero LLM. */
public final class FeatureRenderer {

    public String render(FeatureDraft draft) {
        return "Feature: " + draft.title() + "\n\n" + draft.scenarios().stream()
                .map(s -> "  Scenario: " + s.name() + "\n" + s.steps().lines()
                        .map(l -> "    " + l).collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n\n")) + "\n";
    }
}
