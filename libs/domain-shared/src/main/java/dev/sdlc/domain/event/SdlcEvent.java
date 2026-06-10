package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;

/** Shared event vocabulary; agents coordinate by choreography over these. */
public sealed interface SdlcEvent permits ArtifactChanged, ArtifactProposed, RevalidationRequested {
    ArtifactId subject();
}
