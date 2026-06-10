package dev.sdlc.agent.port;

import dev.sdlc.domain.event.SdlcEvent;

/** Outbound port to the SDLC event bus; agents publish, never consume here. */
@FunctionalInterface
public interface EventPublisherPort {
    void publish(SdlcEvent event);
}
