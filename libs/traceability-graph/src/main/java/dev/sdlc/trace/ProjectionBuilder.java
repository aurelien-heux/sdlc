package dev.sdlc.trace;

import dev.sdlc.domain.NodeStatus;
import dev.sdlc.domain.event.RevalidationRequested;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Rebuilds the projection from artifact files; runs at startup and after writes.
 * Pinned refs (id@sha) make staleness restart-safe: a pin that no longer matches the
 * upstream's current sha is recreated as a STALE edge and the owner flagged, exactly
 * as a live applyChange would have done (FR-TRACE-2). traceability-graph must not
 * depend on agent-core, so events surface through a plain Consumer.
 */
public final class ProjectionBuilder {
    private final FrontmatterParser parser;

    public ProjectionBuilder(FrontmatterParser parser) { this.parser = parser; }

    public void rebuild(Path artifactRoot, TraceabilityGraphPort graph) {
        rebuild(artifactRoot, graph, e -> { });
    }

    public void rebuild(Path artifactRoot, TraceabilityGraphPort graph,
                        Consumer<RevalidationRequested> onStale) {
        List<ArtifactFile> artifacts;
        // workspace/inbox holds RAW stakeholder documents (FR-INT-1) — agent inputs, not
        // artifacts; they carry no frontmatter and must not be projected (nor crash rebuild).
        Path inbox = artifactRoot.resolve("inbox");
        try (Stream<Path> files = Files.walk(artifactRoot)) {
            artifacts = files.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.startsWith(inbox))
                    .map(p -> {
                        try { return parser.parse(Files.readString(p), artifactRoot.relativize(p).toString()); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }).toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }

        artifacts.forEach(a -> graph.upsert(a.node()));
        var staleOwners = new java.util.ArrayList<dev.sdlc.domain.ArtifactId>();
        for (var artifact : artifacts)
            artifact.edgeTargets().forEach((type, targets) -> targets.forEach(target -> {
                var upstreamSha = graph.get(target.id()).map(Node::blobSha).orElse("unknown");
                var pinned = target.pinnedSha();
                boolean stale = pinned != null && !pinned.equals(upstreamSha);
                var linkedSha = pinned != null ? pinned : upstreamSha;
                var edge = Edge.current(type, artifact.node().id(), target.id(), linkedSha,
                        "projection", Instant.now());
                graph.link(stale ? edge.withStatus(dev.sdlc.domain.LinkStatus.STALE) : edge);
                if (stale) {
                    flag(graph, artifact.node().id(), target.id(), onStale);
                    staleOwners.add(artifact.node().id());
                }
            }));
        // Transitive pass: propagate staleness to all nodes downstream of each stale owner.
        // flag() dedupes via the NEEDS_REVALIDATION check so double-events are impossible.
        for (var owner : staleOwners)
            for (var node : graph.impactOf(owner))
                flag(graph, node.id(), owner, onStale);
    }

    private void flag(TraceabilityGraphPort graph, dev.sdlc.domain.ArtifactId owner,
                      dev.sdlc.domain.ArtifactId changedUpstream,
                      Consumer<RevalidationRequested> onStale) {
        graph.get(owner).filter(n -> n.status() != NodeStatus.NEEDS_REVALIDATION).ifPresent(n -> {
            graph.upsert(n.withStatus(NodeStatus.NEEDS_REVALIDATION, n.provenance(), Instant.now()));
            onStale.accept(new RevalidationRequested(owner, List.of(changedUpstream)));
        });
    }
}
