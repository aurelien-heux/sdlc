package dev.sdlc.agentbacklog.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class BacklogDraftTest {
    BacklogItemDraft story = new BacklogItemDraft("story", "Tax at checkout",
            "implement regional tax", "FR VAT", "M", List.of());

    @Test
    void levelAndEstimateAreConstrained() {
        assertThatThrownBy(() -> new BacklogItemDraft("saga", "t", "d", null, "M", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("level");
        assertThatThrownBy(() -> new BacklogItemDraft("story", "t", "d", null, "XXL", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("estimate");
    }

    @Test
    void draftNeedsItemsAndValidDependencyTitles() {
        assertThatThrownBy(() -> new BacklogDraft(List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        var dep = new BacklogItemDraft("story", "Depends on tax", "d", null, "S",
                List.of("Tax at checkout"));
        assertThat(new BacklogDraft(List.of(story, dep)).items()).hasSize(2);
        // dependency on an unknown title is invalid
        assertThatThrownBy(() -> new BacklogDraft(List.of(
                new BacklogItemDraft("story", "x", "d", null, "S", List.of("No such item")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown dependency");
    }
}
