package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies the full L2-L3 summarisation pipeline.
 * Publishes enough moments to the moment bus to trigger a phase window,
 * then ticks the lifecycle and asserts phases appear on the phase bus.
 *
 * Refs #182
 */
@QuarkusTest
class SummarisationPipelineIT {

    @Inject MomentBroker momentBroker;
    @Inject SummarisationLifecycle lifecycle;
    @Inject Event<GameStarted> gameStartedEvent;

    private final List<LevelEvent<GamePhase>> receivedPhases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        receivedPhases.clear();
        lifecycle.phaseBus().subscribe(p -> true, receivedPhases::add);
    }

    @Test
    void fullPipeline_momentsToPhases() {
        var bus = momentBroker.momentBus();
        var level2 = MomentDetectionTask.LEVEL_2;

        // Publish enough moments to trigger the phase window (count threshold = 5)
        for (int i = 0; i < SummarisationLifecycle.PHASE_WINDOW_COUNT; i++) {
            bus.publish(new LevelEvent<>(
                new GameMoment(GameMomentType.BATTLE_STARTED, 100 + i, Map.of()),
                100 + i, level2));
        }

        // Tick the lifecycle — should trigger phase summarisation
        lifecycle.tick(200);

        assertThat(receivedPhases).isNotEmpty();
        assertThat(receivedPhases.get(0).payload().phase()).isEqualTo("MID_SKIRMISH");
    }

    /**
     * C1 regression test: verifies bus subscriptions persist across GameStarted events.
     *
     * <p>Before the fix, MomentBroker.onGameStarted() called {@code momentBus.clear()}
     * which dropped ALL subscriptions, orphaning the L2→L3 pipeline. After GameStarted,
     * moments were published but phases never appeared.
     *
     * <p>After the fix, subscriptions persist — accumulators are cleared but the
     * pipeline stays wired.
     */
    @Test
    void gameStartedReset_subscriptionsPersist() {
        var bus = momentBroker.momentBus();
        var level2 = MomentDetectionTask.LEVEL_2;

        // First game: publish moments and verify phases appear
        for (int i = 0; i < SummarisationLifecycle.PHASE_WINDOW_COUNT; i++) {
            bus.publish(new LevelEvent<>(
                new GameMoment(GameMomentType.BATTLE_STARTED, 100 + i, Map.of()),
                100 + i, level2));
        }
        lifecycle.tick(200);
        assertThat(receivedPhases).as("First game: phases should appear").isNotEmpty();
        int firstGamePhaseCount = receivedPhases.size();

        // Fire GameStarted (simulates game restart)
        gameStartedEvent.fire(new GameStarted());

        // Second game: publish moments again and verify phases still appear
        for (int i = 0; i < SummarisationLifecycle.PHASE_WINDOW_COUNT; i++) {
            bus.publish(new LevelEvent<>(
                new GameMoment(GameMomentType.TECH_TRANSITION_DETECTED, 300 + i, Map.of()),
                300 + i, level2));
        }
        lifecycle.tick(400);

        // Pipeline should still work — more phases should have been published
        assertThat(receivedPhases.size())
            .as("Second game: pipeline still works, phases published")
            .isGreaterThan(firstGamePhaseCount);
    }
}
