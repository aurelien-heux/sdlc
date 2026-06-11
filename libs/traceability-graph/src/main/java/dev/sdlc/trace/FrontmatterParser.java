package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Frontmatter is the canonical source of truth for identity and links (brief §12.1). */
public final class FrontmatterParser {

    public ArtifactFile parse(String content, String repoPath) {
        if (!content.startsWith("---"))
            throw new IllegalArgumentException("missing frontmatter: " + repoPath);
        int end = content.indexOf("\n---", 3);
        if (end < 0) throw new IllegalArgumentException("unterminated frontmatter: " + repoPath);
        String yaml = content.substring(3, end);
        int nl = content.indexOf('\n', end + 1);
        String body = nl < 0 ? "" : content.substring(nl + 1);
        try {
            return toArtifact(content, yaml, body, repoPath);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw new IllegalArgumentException("invalid frontmatter in " + repoPath + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private ArtifactFile toArtifact(String content, String yaml, String body, String repoPath) {
        Map<String, Object> fm = new Yaml().load(yaml);
        if (fm == null) throw new IllegalArgumentException("empty frontmatter");
        Map<String, Object> prov = (Map<String, Object>) fm.getOrDefault("provenance", Map.of());

        var provenance = new Provenance(
                strings(prov.get("sourceRefs")),
                (String) prov.getOrDefault("generatedBy", "unknown"),
                ((Number) prov.getOrDefault("confidence", 0.0)).doubleValue(),
                strings(prov.get("assumptions")),
                Boolean.TRUE.equals(prov.get("humanApproved")),
                (String) prov.get("approvedBy"),
                null);

        var node = new Node(
                ArtifactId.of((String) require(fm, "id")),
                nodeType((String) require(fm, "type")),
                (String) fm.get("title"),
                repoPath,
                gitBlobSha(content),
                NodeStatus.valueOf((String) require(fm, "status")),
                1, provenance, Instant.now(), Instant.now());

        Map<EdgeType, List<EdgeTarget>> edges = new EnumMap<>(EdgeType.class);
        putEdges(edges, EdgeType.DERIVES_FROM, fm.get("derivesFrom"));
        putEdges(edges, EdgeType.CONSTRAINS, fm.get("constrainedBy"));
        putEdges(edges, EdgeType.DEPENDS_ON, fm.get("dependsOn"));
        return new ArtifactFile(node, edges, body, java.util.Collections.unmodifiableMap(fm));
    }

    private static Object require(Map<String, Object> fm, String key) {
        var value = fm.get(key);
        if (value == null) throw new IllegalArgumentException("missing required key: " + key);
        return value;
    }

    private static NodeType nodeType(String value) {
        // frontmatter uses PascalCase (Specification, UseCase); enum is UPPER_SNAKE
        return NodeType.valueOf(value.replaceAll("(?<=[a-z])(?=[A-Z])", "_").toUpperCase(Locale.ROOT));
    }

    private static void putEdges(Map<EdgeType, List<EdgeTarget>> map, EdgeType type, Object raw) {
        var targets = strings(raw).stream().map(EdgeTarget::parse).toList();
        if (!targets.isEmpty()) map.put(type, targets);
    }

    private static List<String> strings(Object raw) {
        if (raw == null) return List.of();
        return ((List<?>) raw).stream().map(Object::toString).toList();
    }

    /** Identical to `git hash-object`: sha1("blob <len>\0<content>"). */
    public static String gitBlobSha(String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
            var sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
