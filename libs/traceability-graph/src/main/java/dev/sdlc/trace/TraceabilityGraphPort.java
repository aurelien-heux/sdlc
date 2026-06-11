package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.EdgeType;
import dev.sdlc.domain.NodeType;
import dev.sdlc.domain.event.RevalidationRequested;
import java.util.List;
import java.util.Optional;

public interface TraceabilityGraphPort {
    Optional<Node> get(ArtifactId id);
    void upsert(Node node);
    void link(Edge edge);
    List<Node> downstreamOf(ArtifactId id, EdgeType... types);
    /** Nodes of the given types; empty = all (ordering unspecified). */
    List<Node> listByType(NodeType... types);
    List<Node> staleNodes();
    List<Node> impactOf(ArtifactId changed);
    void revalidate(String edgeId, String validatedBy);
    /** Apply an upstream content change: stale edges, flag downstream, return events to emit. */
    List<RevalidationRequested> applyChange(ArtifactId nodeId, String newBlobSha);
}
