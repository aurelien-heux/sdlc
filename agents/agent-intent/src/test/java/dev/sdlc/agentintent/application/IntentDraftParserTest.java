package dev.sdlc.agentintent.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IntentDraftParserTest {
    static final String JSON = """
            {"goals": [{"title": "Faster checkout", "description": "Reduce checkout to 5s",
                        "sourceQuotes": ["we lose carts at checkout"], "assumptions": []}],
             "requirements": [{"title": "Apply regional tax", "description": "Tax by shipping region",
                        "kind": "functional", "moscow": "MUST", "goalTitle": "Faster checkout",
                        "sourceQuotes": ["tax must match the region"], "assumptions": []}],
             "useCases": [{"title": "Customer checks out", "actor": "Customer",
                        "mainFlow": ["add items", "pay"], "altFlows": [],
                        "requirementTitle": "Apply regional tax",
                        "sourceQuotes": [], "assumptions": ["flow assumed from context"]}]}
            """;

    @Test
    void parsesAllThreeKinds() {
        var draft = new IntentDraftParser().parse(JSON);
        assertThat(draft.goals()).hasSize(1);
        assertThat(draft.requirements().getFirst().moscow()).isEqualTo("MUST");
        assertThat(draft.useCases().getFirst().requirementTitle()).isEqualTo("Apply regional tax");
    }

    @Test
    void toleratesFenceAndAbsentNullableLinks() {
        var json = """
                {"goals": [{"title": "G", "description": "d", "sourceQuotes": ["q"], "assumptions": []}],
                 "requirements": [], "useCases": []}
                """;
        var draft = new IntentDraftParser().parse("```json\n" + json + "\n```");
        assertThat(draft.goals()).hasSize(1);
    }

    @Test
    void explicitJsonNullLinkTitlesAreTreatedAsAbsent() {
        var draft = new IntentDraftParser().parse("""
                {"goals": [], "useCases": [],
                 "requirements": [{"title": "R", "description": "d", "kind": "functional",
                            "moscow": "MUST", "goalTitle": null,
                            "sourceQuotes": ["q"], "assumptions": []}]}
                """);
        assertThat(draft.requirements().getFirst().goalTitle()).isNull();
    }

    @Test
    void rejectsProseAndUngroundedItems() {
        assertThatThrownBy(() -> new IntentDraftParser().parse("sorry, no json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentDraftParser().parse("""
                {"goals": [{"title": "G", "description": "d", "sourceQuotes": [], "assumptions": []}],
                 "requirements": [], "useCases": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grounded");
    }
}
