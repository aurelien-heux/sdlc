package dev.sdlc.trace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.stream.Stream;

/** Rebuilds the projection from artifact files; in Phase 0 this runs at startup and after writes. */
public final class ProjectionBuilder {
    private final FrontmatterParser parser;

    public ProjectionBuilder(FrontmatterParser parser) { this.parser = parser; }

    public void rebuild(Path artifactRoot, TraceabilityGraphPort graph) {
        try (Stream<Path> files = Files.walk(artifactRoot)) {
            var artifacts = files.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> {
                        try { return parser.parse(Files.readString(p), artifactRoot.relativize(p).toString()); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }).toList();
            artifacts.forEach(a -> graph.upsert(a.node()));
            for (var artifact : artifacts)
                artifact.edgeTargets().forEach((type, targets) -> targets.forEach(to -> {
                    var upstreamSha = graph.get(to).map(Node::blobSha).orElse("unknown");
                    graph.link(Edge.current(type, artifact.node().id(), to, upstreamSha,
                            "projection", Instant.now()));
                }));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
