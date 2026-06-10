package dev.sdlc.trace;

import dev.sdlc.domain.*;
import java.time.Instant;

/** Graph node: identity, version (= git blob sha), status. Content stays in the file. */
public record Node(ArtifactId id, NodeType type, String title, String repoPath,
                   String blobSha, NodeStatus status, int version, Provenance provenance,
                   Instant createdAt, Instant updatedAt) {

    public Node {
        if (status == NodeStatus.APPROVED && !provenance.humanApproved())
            throw new IllegalArgumentException("APPROVED requires provenance.humanApproved");
    }

    /** Content change keeps the node's status (even APPROVED): per brief §12.6 only downstream is flagged. */
    public Node withContentChange(String newBlobSha, Instant at) {
        return new Node(id, type, title, repoPath, newBlobSha, status, version + 1,
                provenance, createdAt, at);
    }

    public Node withStatus(NodeStatus newStatus, Provenance newProvenance, Instant at) {
        return new Node(id, type, title, repoPath, blobSha, newStatus, version,
                newProvenance, createdAt, at);
    }
}
