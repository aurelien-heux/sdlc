package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.EventPublisherPort;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.trace.TraceabilityGraphPort;

/** FR-TRACE-2: inbound ArtifactChanged → propagate staleness → emit RevalidationRequested. */
public final class ArtifactChangedHandler {
    private final TraceabilityGraphPort graph;
    private final EventPublisherPort events;

    public ArtifactChangedHandler(TraceabilityGraphPort graph, EventPublisherPort events) {
        this.graph = graph; this.events = events;
    }

    public void on(ArtifactChanged event) {
        // applyChange is a no-op on unchanged sha and only flags fresh nodes → redelivery is a no-op
        graph.applyChange(event.subject(), event.newBlobSha())
             .forEach(events::publish);
    }
}
