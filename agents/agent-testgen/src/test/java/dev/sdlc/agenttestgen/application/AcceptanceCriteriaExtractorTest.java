package dev.sdlc.agenttestgen.application;

import dev.sdlc.agentspec.domain.AcceptanceCriterion;
import dev.sdlc.agentspec.domain.SpecificationDraft;
import dev.sdlc.agenttestgen.domain.FeatureDraft;
import dev.sdlc.agenttestgen.domain.ScenarioSpec;
import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AcceptanceCriteriaExtractorTest {

    @Test
    void roundTripsTheRealSpecGeneratorBodyFormat() {
        var draft = new SpecificationDraft(ArtifactId.of("SPEC-0001"), "Checkout tax",
                List.of(ArtifactId.of("REQ-0001")),
                List.of(new AcceptanceCriterion("FR VAT",
                                "Given a FR cart\nWhen checkout\nThen VAT added"),
                        new AcceptanceCriterion("Rounding",
                                "Given a total of 10.005\nWhen tax is applied\nThen the total rounds per jurisdiction")),
                List.of("rounding follows jurisdiction rules"));

        var scenarios = new AcceptanceCriteriaExtractor().extract(draft.renderBody(), "SPEC-0001");

        assertThat(scenarios).hasSize(2);
        assertThat(scenarios.getFirst().name()).isEqualTo("FR VAT");
        assertThat(scenarios.getFirst().steps())
                .isEqualTo("Given a FR cart\nWhen checkout\nThen VAT added");
        assertThat(scenarios.get(1).name()).isEqualTo("Rounding");
    }

    @Test
    void interiorBlankLineInsideAScenarioDoesNotDropSteps() {
        // hand-edited spec body: a blank line in the middle of a scenario's steps
        var body = """
                ## Acceptance criteria

                Scenario: FR VAT
                Given a FR cart

                When checkout
                Then VAT added

                Scenario: Rounding
                Given a total of 10.005
                When tax is applied
                Then it rounds per jurisdiction
                """;

        var scenarios = new AcceptanceCriteriaExtractor().extract(body, "SPEC-0001");

        assertThat(scenarios).hasSize(2);
        assertThat(scenarios.getFirst().steps())
                .isEqualTo("Given a FR cart\nWhen checkout\nThen VAT added");
        assertThat(scenarios.get(1).steps())
                .isEqualTo("Given a total of 10.005\nWhen tax is applied\nThen it rounds per jurisdiction");
    }

    @Test
    void sectionHeadingMatchIsAnchoredToLineStart() {
        // a mid-line mention of the heading in prose must not be taken as the section
        var body = """
                ## Notes
                see the ## Acceptance criteria section below

                ## Acceptance criteria

                Scenario: FR VAT
                Given a FR cart
                When checkout
                Then VAT added
                """;

        var scenarios = new AcceptanceCriteriaExtractor().extract(body, "SPEC-0001");

        assertThat(scenarios).hasSize(1);
        assertThat(scenarios.getFirst().name()).isEqualTo("FR VAT");
        assertThat(scenarios.getFirst().steps())
                .isEqualTo("Given a FR cart\nWhen checkout\nThen VAT added");
    }

    @Test
    void missingCriteriaSectionFailsNamingTheSpec() {
        assertThatThrownBy(() -> new AcceptanceCriteriaExtractor().extract("## Notes\nno criteria", "SPEC-0009"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPEC-0009");
    }

    @Test
    void rendererProducesValidCucumberText() {
        var feature = new FeatureRenderer().render(new FeatureDraft(
                "Checkout tax", List.of(new ScenarioSpec(
                        "FR VAT", "Given a FR cart\nWhen checkout\nThen VAT added"))));
        assertThat(feature).startsWith("Feature: Checkout tax")
                .contains("  Scenario: FR VAT")
                .contains("    Given a FR cart")
                .contains("    Then VAT added");
    }
}
