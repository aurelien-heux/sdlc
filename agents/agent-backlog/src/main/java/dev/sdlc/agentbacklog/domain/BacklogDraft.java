package dev.sdlc.agentbacklog.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record BacklogDraft(List<BacklogItemDraft> items) {
    public BacklogDraft {
        items = List.copyOf(items);
        if (items.isEmpty())
            throw new IllegalArgumentException("backlog draft needs at least one item");
        Set<String> titles = items.stream().map(BacklogItemDraft::title).collect(Collectors.toSet());
        for (var item : items)
            for (var dep : item.dependsOn())
                if (!titles.contains(dep))
                    throw new IllegalArgumentException("unknown dependency '" + dep
                            + "' on item '" + item.title() + "'");
    }
}
