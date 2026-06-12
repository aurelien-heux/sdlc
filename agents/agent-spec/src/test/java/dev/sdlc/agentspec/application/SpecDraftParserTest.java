package dev.sdlc.agentspec.application;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SpecDraftParserTest {
    static final String JSON = """
            {"title": "Checkout applies regional tax",
             "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
             "constraints": ["rounding per jurisdiction"],
             "assumptions": ["single currency"],
             "untestable": []}
            """;

    @Test
    void parsesModelJsonIntoDraft() {
        var parsed = new SpecDraftParser().parse(JSON, ArtifactId.of("SPEC-0001"),
                List.of(ArtifactId.of("REQ-0012")));
        assertThat(parsed.draft().title()).isEqualTo("Checkout applies regional tax");
        assertThat(parsed.draft().criteria()).hasSize(1);
        assertThat(parsed.assumptions()).containsExactly("single currency");
        assertThat(parsed.report().clean()).isTrue();
    }

    @Test
    void toleratesMarkdownJsonFence() {
        var parsed = new SpecDraftParser().parse("```json\n" + JSON + "\n```",
                ArtifactId.of("SPEC-0001"), List.of(ArtifactId.of("REQ-0012")));
        assertThat(parsed.draft().title()).isEqualTo("Checkout applies regional tax");
    }

    @Test
    void toleratesStepsAsArrayOfStrings() {
        // some models emit Gherkin steps as a JSON array rather than one newline-joined string
        String json = """
                {"title": "Checkout applies regional tax",
                 "criteria": [{"scenario": "FR VAT", "steps": ["Given a FR cart", "When checkout", "Then VAT added"]}],
                 "constraints": [], "assumptions": [], "untestable": []}
                """;
        var parsed = new SpecDraftParser().parse(json, ArtifactId.of("SPEC-0001"),
                List.of(ArtifactId.of("REQ-0012")));
        assertThat(parsed.draft().criteria().getFirst().steps())
            .isEqualTo("Given a FR cart\nWhen checkout\nThen VAT added");
    }

    @Test
    void rejectsUnparseableOutput() {
        assertThatThrownBy(() -> new SpecDraftParser().parse("sorry, here is prose",
                ArtifactId.of("SPEC-0001"), List.of(ArtifactId.of("REQ-0012"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
