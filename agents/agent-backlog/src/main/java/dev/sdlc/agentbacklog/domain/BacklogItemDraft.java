package dev.sdlc.agentbacklog.domain;

import java.util.List;
import java.util.Set;

/** level: epic|story|task; estimate: XS..XL; acceptanceHook names the Gherkin scenario served (nullable). */
public record BacklogItemDraft(String level, String title, String description,
                               String acceptanceHook, String estimate, List<String> dependsOn) {
    private static final Set<String> LEVELS = Set.of("epic", "story", "task");
    private static final Set<String> ESTIMATES = Set.of("XS", "S", "M", "L", "XL");

    public BacklogItemDraft {
        dependsOn = List.copyOf(dependsOn);
        if (!LEVELS.contains(level))
            throw new IllegalArgumentException("level must be epic|story|task: " + level);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("backlog item title required");
        if (!ESTIMATES.contains(estimate))
            throw new IllegalArgumentException("estimate must be XS|S|M|L|XL: " + estimate);
    }
}
