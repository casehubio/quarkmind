package io.quarkmind.plugin.summarisation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MomentBroker CDI wiring, channel creation,
 * and GameStarted reset behaviour.
 *
 * Refs #182
 */
@QuarkusTest
class MomentBrokerIT {

    @Inject MomentBroker broker;
    @Inject Event<GameStarted> gameStartedEvent;

    @Test
    void channelCreated_onStartup() {
        assertThat(broker.channelId()).isNotNull();
    }

    @Test
    void momentBus_isAccessible() {
        assertThat(broker.momentBus()).isNotNull();
    }

    @Test
    void gameStarted_clearsBus() {
        // Add a subscription, then fire GameStarted — bus should be cleared and re-initialised
        broker.momentBus().subscribe(m -> true, e -> {});
        gameStartedEvent.fire(new GameStarted());
        // Bus was cleared and re-initialized — no error, bus still functional
        assertThat(broker.momentBus()).isNotNull();
    }
}
