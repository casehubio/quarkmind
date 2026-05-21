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
 * <p>Unit count divergence (≤ 2) has two independent causes:
 * <ul>
 *   <li><b>Timing formula</b>: the sub-tick fix (#142) moved firstUnitDivergenceTick from 36
 *       to 86. The remaining 1-tick discrepancy at tick 86 is a completion-time formula issue
 *       — EM's {@code completesAt} rounds to 86 while SC2 completes the unit at tick 87. This
 *       is independent of mineral balance; deducting building costs does not fix it.</li>
 *   <li><b>Gas units</b>: EmulatedGame starts with 0 vespene and has no gas income model.
 *       Train commands for Stalkers (50 gas) and Immortals (100 gas) are rejected, creating a
 *       divergence of 1 unit per gas unit trained in the replay. See #148.</li>
 * </ul>
 *
 * <p>The ≤ 2 bound confirms TrainIntent extraction is working: all train commands are
 * present and applied to the correct building type.
 *
 * Refs #137, #141, #142, #146
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
