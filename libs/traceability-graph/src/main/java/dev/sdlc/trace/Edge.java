package dev.sdlc.trace;

import dev.sdlc.domain.*;
import java.time.Instant;

/** Typed link; upstreamBlobShaAtLink is the to-node's version when last validated. */
public record Edge(String id, EdgeType type, ArtifactId from, ArtifactId to,
                   String upstreamBlobShaAtLink, LinkStatus linkStatus,
                   String establishedBy, Instant validatedAt, String validatedBy) {

    public static Edge current(EdgeType type, ArtifactId from, ArtifactId to,
                               String upstreamSha, String establishedBy, Instant at) {
        return new Edge(from.value() + "->" + to.value() + ":" + type, type, from, to,
                upstreamSha, LinkStatus.CURRENT, establishedBy, at, establishedBy);
    }

    public Edge withStatus(LinkStatus status) {
        return new Edge(id, type, from, to, upstreamBlobShaAtLink, status,
                establishedBy, validatedAt, validatedBy);
    }

    public Edge revalidated(String upstreamSha, String by, Instant at) {
        return new Edge(id, type, from, to, upstreamSha, LinkStatus.CURRENT, establishedBy, at, by);
    }
}
