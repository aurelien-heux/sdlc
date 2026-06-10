package dev.sdlc.agent.port;

import dev.sdlc.domain.event.SdlcEvent;

public interface EventPublisherPort {
    void publish(SdlcEvent event);
}
