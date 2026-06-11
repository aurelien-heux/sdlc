package dev.sdlc.trace;

import dev.sdlc.domain.EdgeType;
import java.util.List;
import java.util.Map;

/**
 * A parsed spec-as-code file: the node plus the upstream refs it links to.
 * {@code rawFrontmatter} is a read-only passthrough of the parsed YAML map for per-type extras;
 * consumers must not treat it as schema.
 */
public record ArtifactFile(Node node, Map<EdgeType, List<EdgeTarget>> edgeTargets, String body,
                            Map<String, Object> rawFrontmatter) {}
