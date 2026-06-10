package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;

/** A node's content changed; newBlobSha is the new version. */
public record ArtifactChanged(ArtifactId subject, String newBlobSha) implements SdlcEvent {}
