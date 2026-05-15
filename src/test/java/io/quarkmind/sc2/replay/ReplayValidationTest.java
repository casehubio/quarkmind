package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates EmulatedGame train-command timing against Nothing_4720936.SC2Replay
 * (PvZ, Nothing = Protoss player 1) to 3 minutes.
 *
 * The harness syncs supply, minerals, vespene, and probe count from ground truth each tick.
 * These are scaffolding inputs — they prevent resource/supply shortfalls from blocking
 * training, isolating what this test actually measures: whether TrainIntent extraction
 * produces the right unit types at the right ticks.
 *
 * Unit count grows apart over time because the real player trains Zealots/Observers from
 * buildings that EmulatedGame doesn't have (bot build commands use abilLink=42 = Smart,
 * indistinguishable from movement — BuildIntent extraction is not attempted). A delta > 5
 * at 3 minutes would indicate a systematic failure in TrainIntent extraction or
 * EmulatedGame's training logic, not merely a building model gap.
 *
 * Mineral and vespene delta tests are omitted in this harness mode: syncing minerals from
 * GT turns the mining model into scaffolding, so the per-tick delta measures building-spend
 * divergence (the real player's mineral drops on buildings emulated doesn't track), not
 * the accuracy of EmulatedGame's own mining rate. A separate harness without resource sync
 * would be needed to validate the mining model independently.
 *
 * Refs #137
 */
class ReplayValidationTest {

    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
    static final int THREE_MINUTES_TICKS = 180;

    @Test
    void unitCountDeltaBoundedForThreeMinutes() {
        DivergenceReport report = ReplayValidationHarness.run(REPLAY, 1, THREE_MINUTES_TICKS);

        assertThat(report.summary().maxUnitDelta())
            .as("Unit count delta must stay ≤ 5 at every tick. Max was %d.\n%s",
                report.summary().maxUnitDelta(), report.renderReport())
            .isLessThanOrEqualTo(5);
    }
}
