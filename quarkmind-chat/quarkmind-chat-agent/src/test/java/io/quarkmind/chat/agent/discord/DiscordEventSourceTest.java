package io.quarkmind.chat.agent.discord;

import io.casehub.blocks.agentic.model.DriverEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DiscordEventSourceTest {

    @Test
    void emitsDriverEventOnInboundMessage() {
        var eventSource = new DiscordEventSource();
        var received = new AtomicReference<DriverEvent>();
        var cancellation = eventSource.subscribe(received::set);

        eventSource.onMessage("ch-1", "user-1", "hello");

        assertNotNull(received.get());
        assertEquals("discord", received.get().source());
        cancellation.cancel();
    }

    @Test
    void cancelledSubscriptionStopsEvents() {
        var eventSource = new DiscordEventSource();
        var received = new AtomicReference<DriverEvent>();
        var cancellation = eventSource.subscribe(received::set);
        cancellation.cancel();

        eventSource.onMessage("ch-1", "user-1", "hello");
        assertNull(received.get());
    }

    @Test
    void multipleSubscribersReceiveEvents() {
        var eventSource = new DiscordEventSource();
        var first = new AtomicReference<DriverEvent>();
        var second = new AtomicReference<DriverEvent>();
        var c1 = eventSource.subscribe(first::set);
        var c2 = eventSource.subscribe(second::set);

        eventSource.onMessage("ch-1", "user-1", "hello");

        assertNotNull(first.get());
        assertNotNull(second.get());
        c1.cancel();
        c2.cancel();
    }
}
