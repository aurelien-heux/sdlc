package dev.sdlc.agentintent.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class IntentDraftTest {
    GoalDraft goal = new GoalDraft("Faster checkout", "Reduce checkout time below 5s",
            List.of("we lose carts at checkout"), List.of());
    RequirementDraft req = new RequirementDraft("Apply regional tax", "Checkout applies shipping-region tax",
            "functional", "MUST", "Faster checkout",
            List.of("tax must match the shipping region"), List.of());
    UseCaseDraft uc = new UseCaseDraft("Customer checks out", "Customer",
            List.of("add items", "checkout", "pay"), List.of("payment fails -> retry"),
            "Apply regional tax", List.of("the flow described in the notes"), List.of());

    @Test
    void draftsRequireGroundingQuotesOrAssumptions() {
        // NFR-GROUND at the draft level: every item cites verbatim source quotes or declares assumptions
        assertThatThrownBy(() -> new GoalDraft("g", "d", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grounded");
        // assumptions alone suffice
        assertThat(new GoalDraft("g", "d", List.of(), List.of("assumed")).assumptions()).hasSize(1);
    }

    @Test
    void requirementKindAndMoscowAreConstrained() {
        assertThatThrownBy(() -> new RequirementDraft("t", "d", "weird", "MUST", null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kind");
        assertThatThrownBy(() -> new RequirementDraft("t", "d", "functional", "MAYBE", null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("moscow");
    }

    @Test
    void intentDraftRequiresAtLeastOneItem() {
        assertThatThrownBy(() -> new IntentDraft(List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        var draft = new IntentDraft(List.of(goal), List.of(req), List.of(uc));
        assertThat(draft.goals()).hasSize(1);
    }

    @Test
    void useCaseRequiresActorAndMainFlow() {
        assertThatThrownBy(() -> new UseCaseDraft("t", " ", List.of("s"), List.of(), null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actor");
        assertThatThrownBy(() -> new UseCaseDraft("t", "Actor", List.of(), List.of(), null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mainFlow");
    }
}
