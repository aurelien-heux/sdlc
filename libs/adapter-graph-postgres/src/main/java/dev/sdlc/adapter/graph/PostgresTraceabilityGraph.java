package dev.sdlc.adapter.graph;

import dev.sdlc.domain.*;
import dev.sdlc.domain.event.RevalidationRequested;
import dev.sdlc.trace.*;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import javax.sql.DataSource;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Postgres projection (spec §12.8). Same behavioral contract as the in-memory adapter —
 * enforced by TraceabilityGraphContract. Plain JDBC; one transaction per mutating call.
 */
public final class PostgresTraceabilityGraph implements TraceabilityGraphPort {
    private final DataSource ds;

    public PostgresTraceabilityGraph(DataSource ds) { this.ds = ds; }

    public void initSchema() {
        try (var in = PostgresTraceabilityGraph.class.getResourceAsStream(
                "/dev/sdlc/adapter/graph/schema.sql");
             var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("schema init failed", e); }
    }

    /** Test hook: wipe both tables. */
    public void truncate() {
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("TRUNCATE nodes, edges");
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public Optional<Node> get(ArtifactId id) {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("SELECT * FROM nodes WHERE id = ?")) {
            ps.setString(1, id.value());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readNode(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public void upsert(Node n) {
        try (var c = ds.getConnection()) { upsert(c, n); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private void upsert(Connection c, Node n) throws SQLException {
        try (var ps = c.prepareStatement("""
                INSERT INTO nodes (id, type, title, repo_path, blob_sha, status, version,
                                   provenance, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
                ON CONFLICT (id) DO UPDATE SET type=EXCLUDED.type, title=EXCLUDED.title,
                  repo_path=EXCLUDED.repo_path, blob_sha=EXCLUDED.blob_sha,
                  status=EXCLUDED.status, version=EXCLUDED.version,
                  provenance=EXCLUDED.provenance, updated_at=EXCLUDED.updated_at""")) {
            ps.setString(1, n.id().value());
            ps.setString(2, n.type().name());
            ps.setString(3, n.title());
            ps.setString(4, n.repoPath());
            ps.setString(5, n.blobSha());
            ps.setString(6, n.status().name());
            ps.setInt(7, n.version());
            ps.setString(8, provenanceJson(n.provenance()));
            ps.setTimestamp(9, Timestamp.from(n.createdAt()));
            ps.setTimestamp(10, Timestamp.from(n.updatedAt()));
            ps.executeUpdate();
        }
    }

    @Override public void link(Edge e) {
        try (var c = ds.getConnection()) { link(c, e); }
        catch (SQLException ex) { throw new IllegalStateException(ex); }
    }

    private void link(Connection c, Edge e) throws SQLException {
        try (var ps = c.prepareStatement("""
                INSERT INTO edges (id, type, from_id, to_id, upstream_blob_sha_at_link,
                                   link_status, established_by, validated_at, validated_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET upstream_blob_sha_at_link=EXCLUDED.upstream_blob_sha_at_link,
                  link_status=EXCLUDED.link_status, validated_at=EXCLUDED.validated_at,
                  validated_by=EXCLUDED.validated_by""")) {
            ps.setString(1, e.id());
            ps.setString(2, e.type().name());
            ps.setString(3, e.from().value());
            ps.setString(4, e.to().value());
            ps.setString(5, e.upstreamBlobShaAtLink());
            ps.setString(6, e.linkStatus().name());
            ps.setString(7, e.establishedBy());
            ps.setTimestamp(8, e.validatedAt() == null ? null : Timestamp.from(e.validatedAt()));
            ps.setString(9, e.validatedBy());
            ps.executeUpdate();
        }
    }

    @Override public List<Node> downstreamOf(ArtifactId id, EdgeType... types) {
        var wanted = types.length == 0
                ? Arrays.stream(EdgeType.values()).map(Enum::name).toList()
                : Arrays.stream(types).map(Enum::name).toList();
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("""
                SELECT n.* FROM edges e JOIN nodes n ON n.id = e.from_id
                WHERE e.to_id = ? AND e.type = ANY (?)""")) {
            ps.setString(1, id.value());
            ps.setArray(2, c.createArrayOf("varchar", wanted.toArray()));
            return readNodes(ps);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public List<Node> staleNodes() {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("SELECT * FROM nodes WHERE status = 'NEEDS_REVALIDATION'")) {
            return readNodes(ps);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public List<Node> impactOf(ArtifactId changed) {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("""
                WITH RECURSIVE impact AS (
                  SELECT e.from_id FROM edges e
                    WHERE e.to_id = ? AND e.type IN ('DERIVES_FROM','SATISFIES')
                  UNION
                  SELECT e.from_id FROM edges e JOIN impact i ON e.to_id = i.from_id
                    WHERE e.type IN ('DERIVES_FROM','SATISFIES')
                )
                SELECT n.* FROM nodes n JOIN impact i ON n.id = i.from_id""")) {
            ps.setString(1, changed.value());
            return readNodes(ps);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public void revalidate(String edgeId, String validatedBy) {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                var edge = findEdge(c, edgeId).orElseThrow(
                        () -> new NoSuchElementException("edge " + edgeId));
                var upstream = findNode(c, edge.to().value()).orElseThrow(
                        () -> new NoSuchElementException("node " + edge.to()));
                if (edge.linkStatus() == LinkStatus.CURRENT
                        && edge.upstreamBlobShaAtLink().equals(upstream.blobSha())) {
                    c.rollback();
                    return; // nothing to revalidate; keep the original audit stamp
                }
                link(c, edge.revalidated(upstream.blobSha(), validatedBy, Instant.now()));
                clearFlagIfNoStaleDeps(c, edge.from());
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private void clearFlagIfNoStaleDeps(Connection c, ArtifactId id) throws SQLException {
        var node = findNode(c, id.value()).orElse(null);
        if (node == null || node.status() != NodeStatus.NEEDS_REVALIDATION) return;
        try (var ps = c.prepareStatement(
                "SELECT count(*) FROM edges WHERE from_id = ? AND link_status = 'STALE'")) {
            ps.setString(1, id.value());
            try (var rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) return;
            }
        }
        var restored = node.provenance().humanApproved() ? NodeStatus.APPROVED : NodeStatus.DRAFT;
        upsert(c, node.withStatus(restored, node.provenance(), Instant.now()));
        try (var ps = c.prepareStatement("""
                SELECT from_id FROM edges WHERE to_id = ? AND type IN ('DERIVES_FROM','SATISFIES')""")) {
            ps.setString(1, id.value());
            try (var rs = ps.executeQuery()) {
                var dependents = new ArrayList<String>();
                while (rs.next()) dependents.add(rs.getString(1));
                for (var d : dependents) clearFlagIfNoStaleDeps(c, ArtifactId.of(d));
            }
        }
    }

    @Override public List<RevalidationRequested> applyChange(ArtifactId nodeId, String newBlobSha) {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                var changed = findNode(c, nodeId.value()).orElseThrow(
                        () -> new NoSuchElementException("node " + nodeId));
                if (changed.blobSha().equals(newBlobSha)) { c.rollback(); return List.of(); }
                upsert(c, changed.withContentChange(newBlobSha, Instant.now()));
                try (var ps = c.prepareStatement("""
                        UPDATE edges SET link_status = 'STALE'
                        WHERE to_id = ? AND type IN ('DERIVES_FROM','SATISFIES')
                          AND upstream_blob_sha_at_link <> ?""")) {
                    ps.setString(1, nodeId.value());
                    ps.setString(2, newBlobSha);
                    ps.executeUpdate();
                }
                var events = new ArrayList<RevalidationRequested>();
                for (var node : impactOf(c, nodeId)) {
                    if (node.status() != NodeStatus.NEEDS_REVALIDATION) {
                        upsert(c, node.withStatus(NodeStatus.NEEDS_REVALIDATION,
                                node.provenance(), Instant.now()));
                        events.add(new RevalidationRequested(node.id(), List.of(nodeId)));
                    }
                }
                c.commit();
                return events;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    // --- helpers ---

    private List<Node> impactOf(Connection c, ArtifactId changed) throws SQLException {
        try (var ps = c.prepareStatement("""
                WITH RECURSIVE impact AS (
                  SELECT e.from_id FROM edges e
                    WHERE e.to_id = ? AND e.type IN ('DERIVES_FROM','SATISFIES')
                  UNION
                  SELECT e.from_id FROM edges e JOIN impact i ON e.to_id = i.from_id
                    WHERE e.type IN ('DERIVES_FROM','SATISFIES')
                )
                SELECT n.* FROM nodes n JOIN impact i ON n.id = i.from_id""")) {
            ps.setString(1, changed.value());
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<Node>();
                while (rs.next()) out.add(readNode(rs));
                return out;
            }
        }
    }

    private Optional<Node> findNode(Connection c, String id) throws SQLException {
        try (var ps = c.prepareStatement("SELECT * FROM nodes WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readNode(rs)) : Optional.empty();
            }
        }
    }

    private Optional<Edge> findEdge(Connection c, String id) throws SQLException {
        try (var ps = c.prepareStatement("SELECT * FROM edges WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                var validatedAt = rs.getTimestamp("validated_at");
                return Optional.of(new Edge(rs.getString("id"),
                        EdgeType.valueOf(rs.getString("type")),
                        ArtifactId.of(rs.getString("from_id")),
                        ArtifactId.of(rs.getString("to_id")),
                        rs.getString("upstream_blob_sha_at_link"),
                        LinkStatus.valueOf(rs.getString("link_status")),
                        rs.getString("established_by"),
                        validatedAt == null ? null : validatedAt.toInstant(),
                        rs.getString("validated_by")));
            }
        }
    }

    private List<Node> readNodes(PreparedStatement ps) throws SQLException {
        try (var rs = ps.executeQuery()) {
            var out = new ArrayList<Node>();
            while (rs.next()) out.add(readNode(rs));
            return out;
        }
    }

    private Node readNode(ResultSet rs) throws SQLException {
        return new Node(ArtifactId.of(rs.getString("id")),
                NodeType.valueOf(rs.getString("type")),
                rs.getString("title"), rs.getString("repo_path"), rs.getString("blob_sha"),
                NodeStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                provenanceFromJson(rs.getString("provenance")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String provenanceJson(Provenance p) {
        var b = Json.createObjectBuilder()
                .add("generatedBy", p.generatedBy())
                .add("confidence", p.confidence())
                .add("humanApproved", p.humanApproved());
        var refs = Json.createArrayBuilder();
        p.sourceRefs().forEach(refs::add);
        var assumptions = Json.createArrayBuilder();
        p.assumptions().forEach(assumptions::add);
        b.add("sourceRefs", refs).add("assumptions", assumptions);
        if (p.approvedBy() != null) b.add("approvedBy", p.approvedBy());
        if (p.approvedAt() != null) b.add("approvedAt", p.approvedAt().toString());
        return b.build().toString();
    }

    private static Provenance provenanceFromJson(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            JsonObject o = r.readObject();
            return new Provenance(
                    o.getJsonArray("sourceRefs").stream()
                            .map(v -> ((jakarta.json.JsonString) v).getString()).toList(),
                    o.getString("generatedBy"),
                    o.getJsonNumber("confidence").doubleValue(),
                    o.getJsonArray("assumptions").stream()
                            .map(v -> ((jakarta.json.JsonString) v).getString()).toList(),
                    o.getBoolean("humanApproved"),
                    o.containsKey("approvedBy") ? o.getString("approvedBy") : null,
                    o.containsKey("approvedAt") ? Instant.parse(o.getString("approvedAt")) : null);
        }
    }
}
