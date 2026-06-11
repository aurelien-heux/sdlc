package dev.sdlc.agentdesign.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DesignDraftParserTest {
    static final String JSON = """
            {"elements": [{"title": "TaxCalculator", "description": "Pure domain service computing regional tax"}],
             "adrs": [{"title": "Use outbox pattern", "context": "events must not be lost",
                       "decision": "transactional outbox",
                       "alternatives": [{"option": "direct publish", "tradeoff": "loses events on crash"},
                                        {"option": "CDC", "tradeoff": "heavy infra"}],
                       "consequences": ["extra relay process"]}],
             "apiContracts": [{"title": "Checkout API", "contract": "POST /checkout -> 200 {total, tax}"}]}
            """;

    @Test
    void parsesAllThreeKinds() {
        var draft = new DesignDraftParser().parse(JSON);
        assertThat(draft.elements()).hasSize(1);
        assertThat(draft.adrs().getFirst().alternatives()).hasSize(2);
        assertThat(draft.apiContracts().getFirst().title()).isEqualTo("Checkout API");
    }

    @Test
    void rejectsSingleAlternativeAdr() {
        assertThatThrownBy(() -> new DesignDraftParser().parse("""
                {"elements": [], "apiContracts": [],
                 "adrs": [{"title": "t", "context": "c", "decision": "d",
                           "alternatives": [{"option": "only", "tradeoff": "x"}], "consequences": []}]}
                """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alternatives");
    }
}
