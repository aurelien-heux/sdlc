package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FrontmatterParserTest {
    static final String DOC = """
            ---
            id: SPEC-0007
            type: Specification
            title: Checkout applies regional tax
            status: APPROVED
            derivesFrom: [REQ-0012, UC-0003]
            constrainedBy: [ADR-0002]
            provenance:
              sourceRefs: [conv:2026-06-03#msg42]
              generatedBy: agent-spec@v3
              confidence: 0.82
              assumptions: ["tax rounding follows local jurisdiction rules"]
              humanApproved: true
              approvedBy: a.dupont
            ---
            ## Acceptance criteria
            Scenario: body is not parsed into the graph
            """;

    @Test
    void parsesNodeAndEdgesFromFrontmatter() {
        ArtifactFile artifact = new FrontmatterParser().parse(DOC, "specs/SPEC-0007.md");

        assertThat(artifact.node().id().value()).isEqualTo("SPEC-0007");
        assertThat(artifact.node().type()).isEqualTo(NodeType.SPECIFICATION);
        assertThat(artifact.node().status()).isEqualTo(NodeStatus.APPROVED);
        assertThat(artifact.node().provenance().approvedBy()).isEqualTo("a.dupont");
        // blobSha = git hash-object of the full content
        assertThat(artifact.node().blobSha()).hasSize(40);
        assertThat(artifact.edgeTargets())
                .containsEntry(EdgeType.DERIVES_FROM,
                        java.util.List.of(ArtifactId.of("REQ-0012"), ArtifactId.of("UC-0003")))
                .containsEntry(EdgeType.CONSTRAINS,
                        java.util.List.of(ArtifactId.of("ADR-0002")));
    }

    @Test
    void rejectsDocumentWithoutFrontmatter() {
        assertThatThrownBy(() -> new FrontmatterParser().parse("no frontmatter", "x.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileWithoutTrailingNewlineAfterClosingFenceHasEmptyBody() {
        var artifact = new FrontmatterParser().parse("""
                ---
                id: REQ-0001
                type: Requirement
                title: t
                status: DRAFT
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: 1.0
                  assumptions: []
                  humanApproved: false
                ---""", "r.md");
        assertThat(artifact.body()).isEmpty();
    }

    @Test
    void malformedFrontmatterFailuresNameTheFile() {
        // missing required keys
        assertThatThrownBy(() -> new FrontmatterParser().parse("---\ntitle: only\n---\nbody", "bad/missing-keys.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad/missing-keys.md");
        // wrong scalar type for confidence
        assertThatThrownBy(() -> new FrontmatterParser().parse("""
                ---
                id: REQ-0001
                type: Requirement
                title: t
                status: DRAFT
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: high
                  assumptions: []
                  humanApproved: false
                ---
                body
                """, "bad/confidence.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad/confidence.md");
    }
}
