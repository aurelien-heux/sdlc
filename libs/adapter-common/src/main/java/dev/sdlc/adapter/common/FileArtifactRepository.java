package dev.sdlc.adapter.common;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.trace.FrontmatterParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Optional;

public final class FileArtifactRepository implements ArtifactRepositoryPort {
    private final Path root;

    public FileArtifactRepository(Path root) { this.root = root; }

    @Override public String write(String repoPath, String content) {
        try {
            Path target = root.resolve(repoPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return FrontmatterParser.gitBlobSha(content);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public Optional<String> read(String repoPath) {
        try {
            Path target = root.resolve(repoPath);
            return Files.exists(target) ? Optional.of(Files.readString(target)) : Optional.empty();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
