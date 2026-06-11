package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human re-validates a stale derives-from link: the downstream artifact's pinned ref is
 * re-stamped to the upstream's current sha in the FILE (canonical), then the graph edge is
 * revalidated. The file rewrite is a metadata change — no propagation is triggered.
 */
public final class RevalidateArtifactUseCase {
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final Supplier<Instant> clock;

    public RevalidateArtifactUseCase(TraceabilityGraphPort graph, ArtifactRepositoryPort repo,
                                     Supplier<Instant> clock) {
        this.graph = graph;
        this.repo = repo;
        this.clock = clock;
    }

    public void revalidate(ArtifactId downstream, ArtifactId upstream, String validatedBy) {
        var down = graph.get(downstream).orElseThrow(() ->
                new IllegalStateException("unknown node " + downstream));
        var up = graph.get(upstream).orElseThrow(() ->
                new IllegalStateException("unknown node " + upstream));

        var content = repo.read(down.repoPath()).orElseThrow(() -> new IllegalStateException(
                "canonical artifact file missing: " + down.repoPath()));
        var rewritten = content.replaceAll(
                Pattern.quote(upstream.value()) + "@[0-9a-f]{40}",
                Matcher.quoteReplacement(upstream.value() + "@" + up.blobSha()));

        // Ordering: revalidate the edge first (which clears the STALE flag and restores node
        // status from provenance), then upsert the sha-updated node to record the new blobSha.
        // This way the status restored by revalidate (APPROVED) is preserved by the subsequent
        // upsert — we call withContentChange which keeps status unchanged (per Node contract).
        graph.revalidate(downstream.value() + "->" + upstream.value() + ":DERIVES_FROM", validatedBy);

        if (!rewritten.equals(content)) {
            var now = clock.get();
            var sha = repo.write(down.repoPath(), rewritten);
            // Fetch the node fresh after revalidate so we don't overwrite the restored status
            var restored = graph.get(downstream).orElseThrow();
            graph.upsert(restored.withContentChange(sha, now));
        }
    }
}
