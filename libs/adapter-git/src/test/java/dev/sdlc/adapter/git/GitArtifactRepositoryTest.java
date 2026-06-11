package dev.sdlc.adapter.git;

import dev.sdlc.adapter.common.FileArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class GitArtifactRepositoryTest {

    private GitArtifactRepository repo(Path dir) {
        var git = new ProcessGitAdapter(dir);
        return new GitArtifactRepository(new FileArtifactRepository(dir), git);
    }

    @Test
    void writeLandsOnProposalBranchNotMain(@TempDir Path dir) {
        var repo = repo(dir);
        String sha = repo.write("specs/SPEC-0001.md", "draft content\n");

        assertThat(sha).hasSize(40);
        // main's working tree does NOT have the file (it lives on the proposal branch)
        assertThat(dir.resolve("specs/SPEC-0001.md")).doesNotExist();
        // but reads fall through to the proposal branch
        assertThat(repo.read("specs/SPEC-0001.md")).contains("draft content\n");
    }

    @Test
    void mergeProposalMakesFileVisibleOnMain(@TempDir Path dir) {
        var repo = repo(dir);
        repo.write("specs/SPEC-0001.md", "draft content\n");
        repo.git().merge("proposal/SPEC-0001", "approval: SPEC-0001 by a.dupont");

        assertThat(dir.resolve("specs/SPEC-0001.md")).exists();
        assertThat(repo.read("specs/SPEC-0001.md")).contains("draft content\n");
    }

    @Test
    void secondWriteToSameArtifactAppendsToItsBranch(@TempDir Path dir) {
        var repo = repo(dir);
        repo.write("specs/SPEC-0001.md", "v1\n");
        repo.write("specs/SPEC-0001.md", "v2\n");
        assertThat(repo.read("specs/SPEC-0001.md")).contains("v2\n");
    }

    @Test
    void nonArtifactPathsCommitDirectlyToMain(@TempDir Path dir) {
        var repo = repo(dir);
        repo.write("inbox/notes.md", "raw stakeholder text\n");
        assertThat(dir.resolve("inbox/notes.md")).exists(); // no proposal dance for non-artifacts
    }
}
