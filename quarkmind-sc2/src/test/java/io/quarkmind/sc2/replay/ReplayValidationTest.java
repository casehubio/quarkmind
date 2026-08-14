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
 * <p>Resources (minerals, vespene) are NOT synced — some divergence is expected. Mining probe
 * count IS synced so the saturation model (#141) produces realistic per-outer-tick income.
 * With the saturation model, mineral delta is bounded at ≤ 1100 over 3 minutes (down from
 * ≤ 11,564 with the old flat model). The remaining mineral delta comes from two sources:
 * (1) income model approximation error (saturation model vs real per-loop SC2 accumulation),
 * and (2) building costs not deducted (buildings are injected free via injectReplayBuilding).
 *
 * <p>Unit count divergence: bounded at ≤ 2 within the 3-minute window. Vespene is synced from
 * pre-tick ground truth (#148), so gas-unit train commands are never rejected. The residual
 * delta comes from ±1 tick sub-tick imprecision in gas-unit training completion timing.
 *
 * Refs #137, #141, #142, #148, #149
 */
class ReplayValidationTest {

    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
    static final int THREE_MINUTES_TICKS = 180;

    @Test
    void unitCountWithinToleranceForThreeMinutes() {
        DivergenceReport report = ReplayValidationHarness.run(REPLAY, 1, THREE_MINUTES_TICKS);

        // Vespene sync (#148) ensures gas-unit train commands never fail for resource reasons.
        // Unit divergence (if any) now starts late (150+) with tiny max delta (2 units),
        // matching observed command extraction calibration issues pre-tick.
        // Building divergence from tick 0 is a pre-existing Nexus tracker timing issue (#142).
        assertThat(report.summary().firstUnitDivergenceTick())
            .as("Unit divergence should be delayed past tick 145 by vespene sync (#148).\n%s",
                report.renderReport())
            .isGreaterThanOrEqualTo(145);

        assertThat(report.summary().maxUnitDelta())
            .as("Max unit delta bounded at 2 by command extraction accuracy (#148).\n%s",
                report.renderReport())
            .isLessThanOrEqualTo(2);
    }

    @Test
    void mineralDeltaWithinToleranceForThreeMinutes() {
        DivergenceReport report = ReplayValidationHarness.run(REPLAY, 1, THREE_MINUTES_TICKS);

        // Mineral delta has two sources: income model approximation (saturation model vs real
        // per-loop SC2 accumulation) and building costs not deducted (injected free).
        // The saturation model (#141) reduced the delta from ~11,564 to ~850 at 3 minutes.
        // Exact parity is not achievable without fixing both sources — see #146, #148.
        assertThat(report.summary().maxMineralDelta())
            .as("Mineral delta must stay ≤ 1100 (saturation model bound). Max was %d.\n%s",
                report.summary().maxMineralDelta(), report.renderReport())
            .isLessThanOrEqualTo(1100);
    }
}
