package io.quarkmind.agent;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.EventLevel;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GamePhaseTriggerTest {

    private static final long EXPECTED_GAME_LENGTH = 20160; // 15 min at 22.4 fps
    private static final double MIN_WEIGHT = 0.1;
    private static final double MAX_WEIGHT = 0.8;
    private static final EventLevel LEVEL_3 = new EventLevel("phase", 3);

    private EventStreamBus<TacticalPosture> phaseBus;
    private GamePhaseTrigger trigger;
    private MilestoneSession session;

    @BeforeEach
    void setUp() {
        phaseBus = new EventStreamBus<>();
        trigger = new GamePhaseTrigger(phaseBus, EXPECTED_GAME_LENGTH, MIN_WEIGHT, MAX_WEIGHT);
        session = new MilestoneSession();
    }

    @Test
    void check_returnsEmpty_whenNoPhaseReceived() {
        assertThat(trigger.check(5000, session)).isEmpty();
    }

    @Test
    void check_firesOnPhaseTransition() {
        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("EARLY_AGGRESSION", 3000, "combat"), 3000, LEVEL_3));

        List<MilestoneEvent> events = trigger.check(3000, session);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).milestoneId()).isEqualTo("phase:EARLY_AGGRESSION");
    }

    @Test
    void check_temporalWeight_proportionalToGameProgress() {
        long frame = 10000;
        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("MID_SKIRMISH", frame, "combat"), frame, LEVEL_3));

        List<MilestoneEvent> events = trigger.check(frame, session);

        double expectedWeight = (double) frame / EXPECTED_GAME_LENGTH; // ~0.496
        assertThat(events.get(0).temporalWeight()).isCloseTo(expectedWeight, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void check_temporalWeight_clampedToMin() {
        long earlyFrame = 500; // 0.025 of game → clamped to 0.1
        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("EARLY_MACRO", earlyFrame, "econ"), earlyFrame, LEVEL_3));

        List<MilestoneEvent> events = trigger.check(earlyFrame, session);

        assertThat(events.get(0).temporalWeight()).isEqualTo(MIN_WEIGHT);
    }

    @Test
    void check_temporalWeight_clampedToMax() {
        long lateFrame = 25000; // beyond expected length → clamped to 0.8
        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("DEFENSIVE_HOLD", lateFrame, "attack"), lateFrame, LEVEL_3));

        List<MilestoneEvent> events = trigger.check(lateFrame, session);

        assertThat(events.get(0).temporalWeight()).isEqualTo(MAX_WEIGHT);
    }

    @Test
    void check_doesNotDoubleFire_samePhase() {
        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("EARLY_AGGRESSION", 3000, "combat"), 3000, LEVEL_3));

        trigger.check(3000, session); // fires and marks
        List<MilestoneEvent> second = trigger.check(3500, session);

        assertThat(second).isEmpty();
    }

    @Test
    void check_firesDifferentPhases() {
        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("EARLY_AGGRESSION", 3000, "combat"), 3000, LEVEL_3));
        trigger.check(3000, session);

        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("MID_SKIRMISH", 8000, "combat"), 8000, LEVEL_3));
        List<MilestoneEvent> events = trigger.check(8000, session);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).milestoneId()).isEqualTo("phase:MID_SKIRMISH");
    }
}
