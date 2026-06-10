package dev.sdlc.agent.port;

import java.util.Optional;

/** Spec-as-code persistence: artifacts are versioned files (FR-SAC-1). */
public interface ArtifactRepositoryPort {
    /** Writes the artifact file and returns its blobSha. */
    String write(String repoPath, String content);
    Optional<String> read(String repoPath);
}
