package dev.sdlc.tools.cli;

import dev.sdlc.trace.FrontmatterParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceCliTest {
    static final String OLD_SHA = "a".repeat(40);

    @TempDir
    Path workspace;

    ByteArrayOutputStream captured;
    WorkspaceCli cli;

    @BeforeEach
    void staleWorkspace() throws IOException {
        // REQ file: current content whose sha != OLD_SHA (pinned in the spec) → stale on rebuild
        String reqContent = "---\nid: REQ-0012\ntype: Requirement\ntitle: r\nstatus: APPROVED\n"
                + "provenance:\n  sourceRefs: [t]\n  generatedBy: h\n  confidence: 1.0\n"
                + "  assumptions: []\n  humanApproved: true\n  approvedBy: a.dupont\n---\nnew content\n";
        Files.createDirectories(workspace.resolve("requirements"));
        Files.writeString(workspace.resolve("requirements/REQ-0012.md"), reqContent);

        String specContent = ("---\nid: SPEC-0001\ntype: Specification\ntitle: s\nstatus: APPROVED\n"
                + "derivesFrom: ['REQ-0012@%s']\n"
                + "provenance:\n  sourceRefs: ['REQ-0012@%s']\n  generatedBy: agent-spec@v1\n"
                + "  confidence: 0.8\n  assumptions: []\n  humanApproved: true\n  approvedBy: a.dupont\n"
                + "---\nbody\n").formatted(OLD_SHA, OLD_SHA);
        Files.createDirectories(workspace.resolve("specs"));
        Files.writeString(workspace.resolve("specs/SPEC-0001.md"), specContent);

        captured = new ByteArrayOutputStream();
        cli = new WorkspaceCli(workspace, new PrintStream(captured, true, StandardCharsets.UTF_8),
                "a.dupont");
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void staleListsFlaggedNodes() {
        int exit = cli.run("stale");

        assertThat(exit).isZero();
        assertThat(output()).contains("SPEC-0001 (SPECIFICATION) — s");
        assertThat(output()).doesNotContain("REQ-0012 (");
    }

    @Test
    void revalidateRestampsPinAndClearsStaleness() throws IOException {
        int exit = cli.run("revalidate", "SPEC-0001", "REQ-0012");

        assertThat(exit).isZero();
        assertThat(output()).contains(
                "SPEC-0001 revalidated against REQ-0012 (now APPROVED) by a.dupont");

        // file pin re-stamped to the REQ's CURRENT sha
        String reqSha = FrontmatterParser.gitBlobSha(
                Files.readString(workspace.resolve("requirements/REQ-0012.md")));
        assertThat(Files.readString(workspace.resolve("specs/SPEC-0001.md")))
                .contains("derivesFrom: ['REQ-0012@" + reqSha + "']");

        // a fresh stale report (fresh rebuild from files) is clean
        captured.reset();
        assertThat(cli.run("stale")).isZero();
        assertThat(output()).contains("nothing needs revalidation");
        assertThat(output()).doesNotContain("SPEC-0001 (");
    }

    @Test
    void staleOnCleanWorkspaceSaysSo() {
        cli.run("revalidate", "SPEC-0001", "REQ-0012");
        captured.reset();

        int exit = cli.run("stale");

        assertThat(exit).isZero();
        assertThat(output()).contains("nothing needs revalidation");
    }

    @Test
    void unknownCommandPrintsUsage() {
        assertThat(cli.run()).isEqualTo(2);
        assertThat(cli.run("frobnicate")).isEqualTo(2);
        assertThat(cli.run("revalidate", "SPEC-0001")).isEqualTo(2);
        assertThat(output()).contains("usage:");
    }

    @Test
    void revalidateUnknownIdFailsWithError() {
        int exit = cli.run("revalidate", "SPEC-9999", "REQ-0012");

        assertThat(exit).isEqualTo(3);
        assertThat(output()).contains("SPEC-9999");
    }
}
