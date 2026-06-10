package dev.sdlc.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ArtifactIdTest {
    @Test
    void acceptsWellFormedIds() {
        assertThat(ArtifactId.of("REQ-0012").value()).isEqualTo("REQ-0012");
        assertThat(ArtifactId.of("SPEC-0007").prefix()).isEqualTo("SPEC");
    }

    @Test
    void rejectsMalformedIds() {
        assertThatThrownBy(() -> ArtifactId.of("req-12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of("REQ12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
