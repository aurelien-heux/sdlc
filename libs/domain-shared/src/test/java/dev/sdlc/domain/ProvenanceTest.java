package dev.sdlc.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ProvenanceTest {
    @Test
    void groundedProvenanceIsValid() {
        var p = Provenance.generated(List.of("conv:2026-06-03#msg42"), "agent-spec@v1", 0.8, List.of());
        assertThat(p.humanApproved()).isFalse();
        assertThat(p.approvedBy()).isNull();
    }

    @Test
    void assumptionsAloneAreSufficientGrounding() {
        var p = Provenance.generated(List.of(), "agent-spec@v1", 0.5, List.of("tax rounding is per-jurisdiction"));
        assertThat(p.assumptions()).hasSize(1);
    }

    @Test
    void emptySourceRefsAndNoAssumptionsIsInvalid() {
        // NFR-GROUND: an artifact with empty sourceRefs and no assumptions is invalid
        assertThatThrownBy(() -> Provenance.generated(List.of(), "agent-spec@v1", 0.5, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("grounded");
    }

    @Test
    void approvalRecordsApprover() {
        var p = Provenance.generated(List.of("ticket:PROJ-88"), "agent-spec@v1", 0.9, List.of())
                .approve("a.dupont", java.time.Instant.parse("2026-06-10T10:00:00Z"));
        assertThat(p.humanApproved()).isTrue();
        assertThat(p.approvedBy()).isEqualTo("a.dupont");
    }
}
