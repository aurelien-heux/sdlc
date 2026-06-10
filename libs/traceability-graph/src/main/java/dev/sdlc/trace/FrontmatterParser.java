package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Frontmatter is the canonical source of truth for identity and links (brief §12.1). */
public final class FrontmatterParser {

    @SuppressWarnings("unchecked")
    public ArtifactFile parse(String content, String repoPath) {
        if (!content.startsWith("---"))
            throw new IllegalArgumentException("missing frontmatter: " + repoPath);
        int end = content.indexOf("\n---", 3);
        if (end < 0) throw new IllegalArgumentException("unterminated frontmatter: " + repoPath);
        String yaml = content.substring(3, end);
        String body = content.substring(content.indexOf('\n', end + 1) + 1);

        Map<String, Object> fm = new Yaml().load(yaml);
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
                ArtifactId.of((String) fm.get("id")),
                nodeType((String) fm.get("type")),
                (String) fm.get("title"),
                repoPath,
                gitBlobSha(content),
                NodeStatus.valueOf((String) fm.get("status")),
                1, provenance, Instant.now(), Instant.now());

        Map<EdgeType, List<ArtifactId>> edges = new EnumMap<>(EdgeType.class);
        putEdges(edges, EdgeType.DERIVES_FROM, fm.get("derivesFrom"));
        putEdges(edges, EdgeType.CONSTRAINS, fm.get("constrainedBy"));
        putEdges(edges, EdgeType.DEPENDS_ON, fm.get("dependsOn"));
        return new ArtifactFile(node, edges, body);
    }

    private static NodeType nodeType(String value) {
        // frontmatter uses PascalCase (Specification, UseCase); enum is UPPER_SNAKE
        return NodeType.valueOf(value.replaceAll("(?<=[a-z])(?=[A-Z])", "_").toUpperCase(Locale.ROOT));
    }

    private static void putEdges(Map<EdgeType, List<ArtifactId>> map, EdgeType type, Object raw) {
        var ids = strings(raw).stream().map(ArtifactId::of).toList();
        if (!ids.isEmpty()) map.put(type, ids);
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
