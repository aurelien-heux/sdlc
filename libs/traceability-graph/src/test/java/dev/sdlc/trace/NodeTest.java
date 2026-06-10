package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class NodeTest {
    static final Provenance PROV =
            Provenance.generated(List.of("doc:brief"), "test", 1.0, List.of());

    @Test
    void nodeCannotBeApprovedWithoutHumanApproval() {
        // brief §12.5: a node cannot reach APPROVED without humanApproved = true
        assertThatThrownBy(() -> new Node(ArtifactId.of("REQ-0001"), NodeType.REQUIREMENT,
                "title", "reqs/REQ-0001.md", "sha1", NodeStatus.APPROVED, 1, PROV,
                Instant.EPOCH, Instant.EPOCH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("humanApproved");
    }

    @Test
    void withContentChangeBumpsVersionAndSha() {
        var node = new Node(ArtifactId.of("REQ-0001"), NodeType.REQUIREMENT, "t",
                "reqs/REQ-0001.md", "sha1", NodeStatus.DRAFT, 1, PROV, Instant.EPOCH, Instant.EPOCH);
        var changed = node.withContentChange("sha2", Instant.parse("2026-06-10T10:00:00Z"));
        assertThat(changed.blobSha()).isEqualTo("sha2");
        assertThat(changed.version()).isEqualTo(2);
    }
}
