package dev.sdlc.agentspec.domain;

import dev.sdlc.domain.ArtifactId;
import java.util.List;

/** What the Specification agent produces before human review. */
public record SpecificationDraft(ArtifactId id, String title, List<ArtifactId> derivesFrom,
                                 List<AcceptanceCriterion> criteria, List<String> constraints) {
    public SpecificationDraft {
        derivesFrom = List.copyOf(derivesFrom);
        criteria = List.copyOf(criteria);
        constraints = List.copyOf(constraints);
        if (criteria.isEmpty())
            throw new IllegalArgumentException("spec needs at least one acceptance criterion");
        if (derivesFrom.isEmpty())
            throw new IllegalArgumentException("spec derives from at least one requirement");
    }

    public String renderBody() {
        var sb = new StringBuilder("## Acceptance criteria\n\n");
        for (var c : criteria)
            sb.append("Scenario: ").append(c.scenario()).append('\n')
              .append(c.steps().strip()).append("\n\n");
        if (!constraints.isEmpty()) {
            sb.append("## Constraints\n\n");
            constraints.forEach(c -> sb.append("- ").append(c).append('\n'));
        }
        return sb.toString();
    }
}
