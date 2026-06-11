package dev.sdlc.agentintent.application;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class IngestInboxUseCaseTest {
    @Test
    void processesEachInboxFileOnceAndMovesIt(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("inbox"));
        Files.writeString(workspace.resolve("inbox/notes.md"), "raw stakeholder text");
        var processed = new java.util.ArrayList<String>();

        var useCase = new IngestInboxUseCase(workspace,
                (path, content) -> { processed.add(path + "::" + content); return List.of(); });
        useCase.ingest();
        useCase.ingest(); // second run: nothing left

        assertThat(processed).containsExactly("inbox/notes.md::raw stakeholder text");
        assertThat(workspace.resolve("inbox/notes.md")).doesNotExist();
        assertThat(workspace.resolve("inbox/processed/notes.md")).exists();
    }

    @Test
    void failureLeavesTheFileInPlace(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("inbox"));
        Files.writeString(workspace.resolve("inbox/bad.md"), "text");

        var useCase = new IngestInboxUseCase(workspace,
                (path, content) -> { throw new IllegalStateException("model exploded"); });

        assertThatThrownBy(useCase::ingest).isInstanceOf(IllegalStateException.class);
        assertThat(workspace.resolve("inbox/bad.md")).exists(); // not moved
    }
}
