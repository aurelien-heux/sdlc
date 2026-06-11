package dev.sdlc.agenttestgen.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FeatureDraftTest {
    ScenarioSpec scenario = new ScenarioSpec("FR VAT",
            "Given a FR cart\nWhen checkout\nThen VAT added");

    @Test
    void scenarioRequiresNameAndGherkinSteps() {
        assertThatThrownBy(() -> new ScenarioSpec(" ", "Given x\nWhen y\nThen z"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> new ScenarioSpec("n", "just prose"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Given/When/Then");
    }

    @Test
    void featureNeedsAtLeastOneScenario() {
        assertThatThrownBy(() -> new FeatureDraft("Checkout tax", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scenario");
        assertThat(new FeatureDraft("Checkout tax", List.of(scenario)).scenarios()).hasSize(1);
    }

    @Test
    void skeletonLanguageIsAllowListed() {
        assertThatThrownBy(() -> new StepSkeletonDraft("cobol", "content"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("language");
        assertThat(new StepSkeletonDraft("java", "class Steps {}").language()).isEqualTo("java");
    }
}
