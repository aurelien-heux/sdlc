package dev.sdlc.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ArtifactIdTest {
    @Test
    void acceptsWellFormedIds() {
        assertThat(ArtifactId.of("REQ-0012").value()).isEqualTo("REQ-0012");
        assertThat(ArtifactId.of("SPEC-0007").prefix()).isEqualTo("SPEC");
        assertThat(ArtifactId.of("NFR-0004").value()).isEqualTo("NFR-0004");
        assertThat(ArtifactId.of("STORY-0042").prefix()).isEqualTo("STORY");
    }

    @Test
    void rejectsMalformedIds() {
        assertThatThrownBy(() -> ArtifactId.of("req-0012")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of("REQ12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of("REQ-00123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of("R-0012")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void toStringIsTheBareValue() {
        assertThat(ArtifactId.of("REQ-0012")).hasToString("REQ-0012");
    }
}
