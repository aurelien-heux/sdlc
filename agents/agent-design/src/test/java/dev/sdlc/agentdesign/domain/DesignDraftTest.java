package dev.sdlc.agentdesign.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DesignDraftTest {
    AdrDraft adr = new AdrDraft("Use outbox pattern", "events must not be lost",
            "Use a transactional outbox",
            List.of(new AdrDraft.Alternative("Direct publish", "simpler but loses events on crash"),
                    new AdrDraft.Alternative("CDC", "robust but heavy infra")),
            List.of("extra table and relay process"));

    @Test
    void adrRequiresAtLeastTwoConsideredAlternatives() {
        // FR-DES-3 structurally: no silent decisions
        assertThatThrownBy(() -> new AdrDraft("t", "c", "d",
                List.of(new AdrDraft.Alternative("only one", "x")), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alternatives");
    }

    @Test
    void draftNeedsAtLeastOneArtifact() {
        assertThatThrownBy(() -> new DesignDraft(List.of(), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        assertThat(new DesignDraft(List.of(), List.of(adr), List.of()).adrs()).hasSize(1);
    }

    @Test
    void elementAndContractRequireTitleAndBody() {
        assertThatThrownBy(() -> new DesignElementDraft(" ", "desc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiContractDraft("Checkout API", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
