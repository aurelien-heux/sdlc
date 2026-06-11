package dev.sdlc.agenttestgen.application;

import dev.sdlc.agenttestgen.domain.ScenarioSpec;

import java.util.ArrayList;
import java.util.List;

/** Deterministically lifts scenarios from a spec body's "## Acceptance criteria" section
 *  (the exact format SpecificationDraft.renderBody writes — round-trip tested against it). */
public final class AcceptanceCriteriaExtractor {

    public List<ScenarioSpec> extract(String specBody, String specContext) {
        int start = specBody.indexOf("## Acceptance criteria");
        if (start < 0)
            throw new IllegalArgumentException("no acceptance criteria section in " + specContext);
        int nextSection = specBody.indexOf("\n## ", start + 1);
        String section = nextSection < 0 ? specBody.substring(start) : specBody.substring(start, nextSection);

        var scenarios = new ArrayList<ScenarioSpec>();
        String name = null;
        var steps = new StringBuilder();
        for (var line : section.lines().toList()) {
            if (line.startsWith("Scenario: ")) {
                flush(scenarios, name, steps);
                name = line.substring("Scenario: ".length()).strip();
                steps.setLength(0);
            } else if (name != null && !line.isBlank()) {
                if (!steps.isEmpty()) steps.append('\n');
                steps.append(line.strip());
            } else if (name != null && line.isBlank() && !steps.isEmpty()) {
                flush(scenarios, name, steps);
                name = null;
                steps.setLength(0);
            }
        }
        flush(scenarios, name, steps);
        if (scenarios.isEmpty())
            throw new IllegalArgumentException("acceptance criteria section has no scenarios in " + specContext);
        return scenarios;
    }

    private static void flush(List<ScenarioSpec> out, String name, StringBuilder steps) {
        if (name != null && !steps.isEmpty()) out.add(new ScenarioSpec(name, steps.toString()));
    }
}
