package dev.sdlc.adapter.common;

import dev.sdlc.agent.port.EventPublisherPort;
import dev.sdlc.domain.event.SdlcEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Phase 0 bus: in-process dispatch + an inspectable log. Phase 2 swaps in real messaging. */
public final class InProcessEventPublisher implements EventPublisherPort {
    private final List<Consumer<SdlcEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final List<SdlcEvent> log = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<SdlcEvent> subscriber) { subscribers.add(subscriber); }
    public List<SdlcEvent> log() { return List.copyOf(log); }

    @Override public void publish(SdlcEvent event) {
        log.add(event);
        subscribers.forEach(s -> s.accept(event));
    }
}
