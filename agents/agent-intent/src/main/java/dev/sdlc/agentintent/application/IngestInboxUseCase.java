package dev.sdlc.agentintent.application;

import dev.sdlc.domain.ArtifactId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** FR-INT-1: each inbox file is processed once; success moves it to inbox/processed/. */
public final class IngestInboxUseCase {
    /** (workspace-relative path, content) -> produced artifact ids. */
    @FunctionalInterface
    public interface SourceProcessor {
        List<ArtifactId> process(String path, String content);
    }

    private final Path workspace;
    private final SourceProcessor processor;

    public IngestInboxUseCase(Path workspace, SourceProcessor processor) {
        this.workspace = workspace; this.processor = processor;
    }

    /** Returns ids produced across all newly processed files. */
    public List<ArtifactId> ingest() {
        var inbox = workspace.resolve("inbox");
        if (!Files.isDirectory(inbox)) return List.of();
        var produced = new ArrayList<ArtifactId>();
        try (Stream<Path> files = Files.list(inbox)) {
            for (var file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted().toList()) {
                var rel = workspace.relativize(file).toString().replace('\\', '/');
                var content = Files.readString(file);
                produced.addAll(processor.process(rel, content)); // throws -> file stays put
                var processedDir = inbox.resolve("processed");
                Files.createDirectories(processedDir);
                Files.move(file, processedDir.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return produced;
    }
}
