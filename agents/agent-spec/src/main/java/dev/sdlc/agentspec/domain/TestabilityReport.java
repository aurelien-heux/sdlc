package dev.sdlc.agentspec.domain;

import dev.sdlc.domain.ArtifactId;
import java.util.List;

/** FR-SPEC-3: requirements the agent could not make testable, surfaced not silenced. */
public record TestabilityReport(List<ArtifactId> covered, List<Flag> flags) {
    public record Flag(ArtifactId requirement, String reason) {}
    public boolean clean() { return flags.isEmpty(); }
}
