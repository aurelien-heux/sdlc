package dev.sdlc.adapter.common;

import dev.sdlc.trace.FrontmatterParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class FileArtifactRepositoryTest {
    @Test
    void writesFileAndReturnsGitCompatibleBlobSha(@TempDir Path root) {
        var repo = new FileArtifactRepository(root);
        String sha = repo.write("specs/SPEC-0001.md", "content\n");

        assertThat(root.resolve("specs/SPEC-0001.md")).exists();
        assertThat(sha).isEqualTo(FrontmatterParser.gitBlobSha("content\n"));
        assertThat(repo.read("specs/SPEC-0001.md")).contains("content\n");
        assertThat(repo.read("missing.md")).isEmpty();
    }
}
