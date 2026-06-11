package dev.sdlc.adapter.common;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class FileBacklogAdapterTest {
    Map<String, String> files = new HashMap<>();
    ArtifactRepositoryPort repo = new ArtifactRepositoryPort() {
        public String write(String path, String content) { files.put(path, content); return "sha"; }
        public Optional<String> read(String path) { return Optional.ofNullable(files.get(path)); }
    };

    @Test
    void upsertWritesUnderBacklogAndTracksOpenItems() {
        var adapter = new FileBacklogAdapter(repo);
        var ref = adapter.upsert(ArtifactId.of("STORY-0001"), "story", "t", "full file content", "M");

        assertThat(ref).isEqualTo("backlog/STORY-0001.md");
        assertThat(files).containsKey("backlog/STORY-0001.md");
        assertThat(adapter.find(ArtifactId.of("STORY-0001"))).contains("backlog/STORY-0001.md");
        assertThat(adapter.listOpen()).containsExactly(ArtifactId.of("STORY-0001"));
        assertThat(adapter.find(ArtifactId.of("STORY-0099"))).isEmpty();
    }
}
