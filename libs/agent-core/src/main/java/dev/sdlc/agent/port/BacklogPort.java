package dev.sdlc.agent.port;

import dev.sdlc.domain.ArtifactId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port to the backlog tool. Phase 1B: file-based; Jira/Linear/ADO later.
 * The file adapter persists {@code body} verbatim at the returned path; the caller owns
 * content rendering and sha computation.
 */
public interface BacklogPort {
    /** level: epic|story|task. estimate: XS|S|M|L|XL. Returns the externalRef (file path for now). */
    String upsert(ArtifactId id, String level, String title, String body, String estimate);
    Optional<String> find(ArtifactId id);
    List<ArtifactId> listOpen();
}
