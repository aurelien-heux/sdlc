package dev.sdlc.agenttestgen.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StepSkeletonParserTest {
    @Test
    void parsesLanguageAndContent() {
        var draft = new StepSkeletonParser().parse("""
                {"language": "java", "content": "public class CheckoutSteps { /* ... */ }"}
                """);
        assertThat(draft.language()).isEqualTo("java");
        assertThat(draft.content()).contains("CheckoutSteps");
    }

    @Test
    void rejectsUnterminatedFenceInsteadOfCrashing() {
        assertThatThrownBy(() -> new StepSkeletonParser().parse(
                "```json\n{\"language\": \"java\", \"content\": \"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unterminated fence");
    }

    @Test
    void rejectsProseAndUnknownLanguage() {
        assertThatThrownBy(() -> new StepSkeletonParser().parse("here you go!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepSkeletonParser().parse(
                "{\"language\": \"cobol\", \"content\": \"x\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("language");
    }
}
