package dev.sdlc.agentspec.domain;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SpecificationDraftTest {
    AcceptanceCriterion gherkin = new AcceptanceCriterion(
            "Checkout applies regional tax",
            """
            Given a cart with items shipped to region FR
            When the customer checks out
            Then VAT at the FR rate is added to the total
            """);

    @Test
    void draftRequiresAtLeastOneAcceptanceCriterion() {
        // FR-SPEC-2/3: a spec without criteria is not testable
        assertThatThrownBy(() -> new SpecificationDraft(
                ArtifactId.of("SPEC-0007"), "Checkout tax",
                List.of(ArtifactId.of("REQ-0012")), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("acceptance criterion");
    }

    @Test
    void draftRequiresAtLeastOneSourceRequirement() {
        // FR-SPEC-1: a spec must derive from approved requirements
        assertThatThrownBy(() -> new SpecificationDraft(
                ArtifactId.of("SPEC-0007"), "Checkout tax",
                List.of(), List.of(gherkin), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("derives");
    }

    @Test
    void rendersMarkdownBodyWithGherkin() {
        var draft = new SpecificationDraft(ArtifactId.of("SPEC-0007"), "Checkout tax",
                List.of(ArtifactId.of("REQ-0012")), List.of(gherkin),
                List.of("rounding follows jurisdiction rules"));
        String body = draft.renderBody();
        assertThat(body).contains("## Acceptance criteria")
                .contains("Scenario: Checkout applies regional tax")
                .contains("Given a cart")
                .contains("## Constraints");
    }
}
