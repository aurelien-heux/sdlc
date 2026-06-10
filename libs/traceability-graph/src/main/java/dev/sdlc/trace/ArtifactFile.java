package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.EdgeType;
import java.util.List;
import java.util.Map;

/** A parsed spec-as-code file: the node plus the upstream ids it links to. */
public record ArtifactFile(Node node, Map<EdgeType, List<ArtifactId>> edgeTargets, String body) {}
