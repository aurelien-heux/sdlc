package dev.sdlc.agenttestgen.application;

import dev.sdlc.agenttestgen.domain.ScenarioSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Deterministically lifts scenarios from a spec body's "## Acceptance criteria" section
 *  (the exact format SpecificationDraft.renderBody writes — round-trip tested against it). */
public final class AcceptanceCriteriaExtractor {
    // anchored: a mid-line mention of the heading (e.g. in prose) must not match
    private static final Pattern SECTION_HEADING =
            Pattern.compile("(?m)^## Acceptance criteria\\s*$");

    public List<ScenarioSpec> extract(String specBody, String specContext) {
        var heading = SECTION_HEADING.matcher(specBody);
        if (!heading.find())
            throw new IllegalArgumentException("no acceptance criteria section in " + specContext);
        int start = heading.start();
        int nextSection = specBody.indexOf("\n## ", start + 1);
        String section = nextSection < 0 ? specBody.substring(start) : specBody.substring(start, nextSection);

        var scenarios = new ArrayList<ScenarioSpec>();
        String name = null;
        var steps = new StringBuilder();
        for (var line : section.lines().toList()) {
            if (line.startsWith("Scenario: ")) {
                // a scenario ends only at the NEXT scenario or the section end; interior
                // blank lines (hand-edited specs) are skipped, never treated as terminators
                flush(scenarios, name, steps);
                name = line.substring("Scenario: ".length()).strip();
                steps.setLength(0);
            } else if (name != null && !line.isBlank()) {
                if (!steps.isEmpty()) steps.append('\n');
                steps.append(line.strip());
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
