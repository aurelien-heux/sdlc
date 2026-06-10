package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;

/** An agent produced a new artifact awaiting human review. */
public record ArtifactProposed(ArtifactId subject) implements SdlcEvent {}
