package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates EmulatedGame train-command accuracy against Nothing_4720936.SC2Replay
 * (PvZ, Nothing = Protoss player 1) to 3 minutes.
 *
 * <p>Buildings are injected from replay tracker events into EmulatedGame at each tick,
 * using the same replay tags. TrainIntents (which carry replay building tags) are applied
 * directly — no tag remapping needed.
 *
 * <p>Supply is synced from GT: the real player builds Pylons we cannot reconstruct from
 * game events, so without supply sync training would halt at the initial 15-supply cap.
 *
 * <p>Resources (minerals, vespene) are NOT synced — divergence is expected because
 * EmulatedGame does not deduct building costs (buildings are injected free). Mining probe
 * count IS synced so the saturation model (#141) produces realistic per-outer-tick income.
 * With the saturation model, mineral delta is bounded at ≤ 1100 over 3 minutes (down from
 * ≤ 11,564 with the old flat model).
 *
 * <p>Unit count divergence (≤ 2) is NOT caused by mineral accumulation — it persists with
 * the saturation model in place. The remaining cause is train-timing precision: the sub-tick
 * fix (#142) corrected loop-offset rounding in startTraining, moving firstUnitDivergenceTick
 * from 36 to 86. Divergence beyond tick 86 is building-cost timing only — see #146.
 *
 * <p>The ≤ 2 bound confirms TrainIntent extraction is working: all train commands are
 * present and applied to the correct building type.
 *
 * Refs #137, #141, #142
 */
class ReplayValidationTest {

    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
    static final int THREE_MINUTES_TICKS = 180;

    @Test
    void unitCountWithinTwoOfGroundTruthForThreeMinutes() {
        DivergenceReport report = ReplayValidationHarness.run(REPLAY, 1, THREE_MINUTES_TICKS);

        assertThat(report.summary().firstUnitDivergenceTick())
            .as("Sub-tick fix (#142) must keep first divergence at or above tick 80 "
                + "(was 36 before the fix; now 86 — mineral-timing gap tracked in #146). "
                + "First divergence was at tick %d.\n%s",
                report.summary().firstUnitDivergenceTick(), report.renderReport())
            .isGreaterThanOrEqualTo(80);

        assertThat(report.summary().maxUnitDelta())
            .as("Unit count delta must stay ≤ 2 at every tick. "
                + "Saturation model (#141) and sub-tick fix (#142) both in place; "
                + "remaining divergence is building-cost timing (#146). "
                + "Max was %d.\n%s",
                report.summary().maxUnitDelta(), report.renderReport())
            .isLessThanOrEqualTo(2);
    }

    @Test
    void mineralDeltaWithinToleranceForThreeMinutes() {
        DivergenceReport report = ReplayValidationHarness.run(REPLAY, 1, THREE_MINUTES_TICKS);

        // Emulated minerals exceed GT because buildings are injected free (no cost deducted).
        // The saturation mining model (#141) reduced the residual delta from ~11,564 to ~850
        // at the 3-minute mark. Exact parity is impossible without deducting building costs.
        assertThat(report.summary().maxMineralDelta())
            .as("Mineral delta must stay ≤ 1100 (saturation model bound). Max was %d.\n%s",
                report.summary().maxMineralDelta(), report.renderReport())
            .isLessThanOrEqualTo(1100);
    }
}
