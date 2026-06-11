package dev.sdlc.agentbacklog.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BacklogDraftParserTest {
    @Test
    void parsesItemsWithDependencies() {
        var draft = new BacklogDraftParser().parse("""
                {"items": [
                  {"level": "epic", "title": "Checkout tax", "description": "everything tax",
                   "acceptanceHook": null, "estimate": "L", "dependsOn": []},
                  {"level": "story", "title": "Regional rate lookup", "description": "lookup by region",
                   "acceptanceHook": "FR VAT", "estimate": "M", "dependsOn": ["Checkout tax"]}
                ]}
                """);
        assertThat(draft.items()).hasSize(2);
        assertThat(draft.items().get(1).dependsOn()).containsExactly("Checkout tax");
    }

    @Test
    void rejectsProse() {
        assertThatThrownBy(() -> new BacklogDraftParser().parse("no json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
