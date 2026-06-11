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
                        java.util.List.of(new EdgeTarget(ArtifactId.of("REQ-0012"), null),
                                new EdgeTarget(ArtifactId.of("UC-0003"), null)))
                .containsEntry(EdgeType.CONSTRAINS,
                        java.util.List.of(new EdgeTarget(ArtifactId.of("ADR-0002"), null)));
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
    void parsesPinnedEdgeRefs() {
        var artifact = new FrontmatterParser().parse("""
                ---
                id: SPEC-0001
                type: Specification
                title: t
                status: DRAFT
                derivesFrom: ['REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', UC-0003]
                verifies: [SPEC-0001]
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: 1.0
                  assumptions: []
                  humanApproved: false
                ---
                body
                """, "s.md");
        assertThat(artifact.edgeTargets().get(EdgeType.DERIVES_FROM)).containsExactly(
                new EdgeTarget(ArtifactId.of("REQ-0012"), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                new EdgeTarget(ArtifactId.of("UC-0003"), null));
        // bare ref = unpinned (VERIFIES is unpinned by design — see GenerateTestsUseCase)
        assertThat(artifact.edgeTargets().get(EdgeType.VERIFIES)).containsExactly(
                new EdgeTarget(ArtifactId.of("SPEC-0001"), null));
    }

    @Test
    void rejectsMalformedPin() {
        assertThatThrownBy(() -> new FrontmatterParser().parse("""
                ---
                id: SPEC-0001
                type: Specification
                title: t
                status: DRAFT
                derivesFrom: ['REQ-0012@nothex']
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: 1.0
                  assumptions: []
                  humanApproved: false
                ---
                body
                """, "s.md"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("s.md");
    }

    @Test
    void rawFrontmatterExposesPerTypeExtras() {
        var artifact = new FrontmatterParser().parse("""
                ---
                id: STORY-0001
                type: BacklogItem
                title: 'Regional rate lookup'
                status: PROPOSED
                level: story
                estimate: M
                acceptanceHook: 'FR VAT'
                derivesFrom: []
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: 0.7
                  assumptions: []
                  humanApproved: false
                ---
                body
                """, "backlog/STORY-0001.md");
        assertThat(artifact.rawFrontmatter().get("acceptanceHook")).isEqualTo("FR VAT");
        assertThat(artifact.rawFrontmatter().get("level")).isEqualTo("story");
        assertThatThrownBy(() -> artifact.rawFrontmatter().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
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
