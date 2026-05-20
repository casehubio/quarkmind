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
 * <p>Resources (minerals, vespene) are NOT synced — divergence is expected given
 * EmulatedGame's flat mining rate vs SC2's saturation-based model. Mining probe count IS
 * synced (scaled by LOOPS_PER_TICK) so the flat-rate model produces the correct per-tick
 * income. Even so, emulated minerals accumulate ~1800 more than GT because the flat model
 * has no saturation cap, which causes emulated to execute some train commands 1 tick early
 * (resource is never the bottleneck in emulated, occasionally is in GT). The resulting unit
 * count divergence is bounded at ≤ 2 and clears within 1 tick.
 *
 * <p>The sub-tick fix (#142) corrected loop-offset rounding in startTraining, moving
 * firstUnitDivergenceTick from 36 to 86. The remaining divergence is mineral-timing only
 * and requires saturation-aware mining (#141) or mineral sync to eliminate — see #146.
 *
 * Refs #137, #142
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
            .as("Unit count delta must stay ≤ 2 at every tick (flat mining model trains 1 tick "
                + "early when emulated minerals exceed GT; exact match requires #141 or #146). "
                + "Max was %d.\n%s",
                report.summary().maxUnitDelta(), report.renderReport())
            .isLessThanOrEqualTo(2);
    }

    @Test
    void mineralDeltaWithinToleranceForThreeMinutes() {
        DivergenceReport report = ReplayValidationHarness.run(REPLAY, 1, THREE_MINUTES_TICKS);

        // Emulated minerals exceed GT because the flat model has no saturation cap.
        // The GT mineral level reflects actual SC2 spending; emulated hoards income.
        // A 2000-mineral delta is expected; this test documents it, not bounds it.
        // Exact mineral accuracy requires the saturation model from #141.
        assertThat(report.summary().maxMineralDelta())
            .as("Mineral delta is expected to be large (flat vs saturation mining). Max was %d.\n%s",
                report.summary().maxMineralDelta(), report.renderReport())
            .isLessThan(5000); // just a sanity-level upper bound
    }
}
