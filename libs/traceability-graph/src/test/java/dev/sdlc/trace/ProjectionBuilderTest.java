package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.LinkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectionBuilderTest {
    @Test
    void rebuildsGraphFromArtifactDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("REQ-0012.md"), """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Checkout applies regional tax
                status: DRAFT
                derivesFrom: [REQ-0012]
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: false
                ---
                body
                """);

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);

        assertThat(graph.get(ArtifactId.of("SPEC-0007"))).isPresent();
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0012")))
                .extracting(n -> n.id().value()).containsExactly("SPEC-0007");
    }
}
