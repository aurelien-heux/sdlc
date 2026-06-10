package dev.sdlc.trace;

import dev.sdlc.domain.*;
import dev.sdlc.domain.event.RevalidationRequested;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Phase 0 projection. Phase 1 replaces this with a Postgres adapter behind the same port. */
public final class InMemoryTraceabilityGraph implements TraceabilityGraphPort {
    private final Map<ArtifactId, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Edge> edges = new ConcurrentHashMap<>();

    @Override public Optional<Node> get(ArtifactId id) { return Optional.ofNullable(nodes.get(id)); }
    @Override public void upsert(Node node) { nodes.put(node.id(), node); }
    @Override public void link(Edge edge) { edges.put(edge.id(), edge); }

    @Override
    public List<Node> downstreamOf(ArtifactId id, EdgeType... types) {
        var wanted = types.length == 0 ? EnumSet.allOf(EdgeType.class) : EnumSet.of(types[0], types);
        return edges.values().stream()
                .filter(e -> e.to().equals(id) && wanted.contains(e.type()))
                .map(e -> nodes.get(e.from())).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Node> staleNodes() {
        return nodes.values().stream().filter(n -> n.status() == NodeStatus.NEEDS_REVALIDATION).toList();
    }

    @Override
    public List<Node> impactOf(ArtifactId changed) {
        var impacted = new LinkedHashMap<ArtifactId, Node>();
        collectDownstream(changed, impacted);
        return List.copyOf(impacted.values());
    }

    private void collectDownstream(ArtifactId id, Map<ArtifactId, Node> acc) {
        for (var node : downstreamOf(id, EdgeType.DERIVES_FROM, EdgeType.SATISFIES)) {
            if (acc.putIfAbsent(node.id(), node) == null) collectDownstream(node.id(), acc);
        }
    }

    @Override
    public void revalidate(String edgeId, String validatedBy) {
        var edge = edges.get(edgeId);
        if (edge == null) throw new NoSuchElementException("edge " + edgeId);
        var upstream = nodes.get(edge.to());
        var now = Instant.now();
        edges.put(edgeId, edge.revalidated(upstream.blobSha(), validatedBy, now));
        // if the from-node has no remaining stale inbound deps, clear its flag
        var from = nodes.get(edge.from());
        boolean stillStale = edges.values().stream()
                .anyMatch(e -> e.from().equals(from.id()) && e.linkStatus() == LinkStatus.STALE);
        if (!stillStale && from.status() == NodeStatus.NEEDS_REVALIDATION)
            nodes.put(from.id(), from.withStatus(NodeStatus.APPROVED, from.provenance(), now));
    }

    @Override
    public List<RevalidationRequested> applyChange(ArtifactId nodeId, String newBlobSha) {
        var changed = nodes.get(nodeId);
        if (changed == null) throw new NoSuchElementException("node " + nodeId);
        nodes.put(nodeId, changed.withContentChange(newBlobSha, Instant.now()));

        var events = new ArrayList<RevalidationRequested>();
        // direct dependents whose link sha no longer matches → edge STALE
        edges.values().stream()
                .filter(e -> e.to().equals(nodeId)
                        && (e.type() == EdgeType.DERIVES_FROM || e.type() == EdgeType.SATISFIES)
                        && !e.upstreamBlobShaAtLink().equals(newBlobSha))
                .toList()
                .forEach(e -> edges.put(e.id(), e.withStatus(LinkStatus.STALE)));
        // flag every transitive dependent (flag only, no sha bump — brief §12.6)
        for (var node : impactOf(nodeId)) {
            if (node.status() != NodeStatus.NEEDS_REVALIDATION) {
                nodes.put(node.id(), node.withStatus(NodeStatus.NEEDS_REVALIDATION,
                        node.provenance(), Instant.now()));
                events.add(new RevalidationRequested(node.id(), List.of(nodeId)));
            }
        }
        return events;
    }
}
