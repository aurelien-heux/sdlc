package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SdlcEventTest {
    @Test
    void eventsCarryTheSubjectNode() {
        SdlcEvent changed = new ArtifactChanged(ArtifactId.of("REQ-0012"), "abc123");
        SdlcEvent proposed = new ArtifactProposed(ArtifactId.of("SPEC-0007"));
        SdlcEvent revalidate = new RevalidationRequested(ArtifactId.of("SPEC-0007"),
                List.of(ArtifactId.of("REQ-0012")));
        assertThat(changed.subject().value()).isEqualTo("REQ-0012");
        assertThat(proposed.subject().value()).isEqualTo("SPEC-0007");
        assertThat(revalidate.subject().value()).isEqualTo("SPEC-0007");
    }
}
