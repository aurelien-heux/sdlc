package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;
import java.util.List;

/** Subject became stale because the listed upstream nodes changed. */
public record RevalidationRequested(ArtifactId subject, List<ArtifactId> changedUpstream) implements SdlcEvent {}
