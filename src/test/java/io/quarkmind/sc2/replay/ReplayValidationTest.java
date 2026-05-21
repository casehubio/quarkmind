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
 * <p>Unit count divergence has one remaining cause:
 * <ul>
 *   <li><b>Gas units</b>: EmulatedGame starts with 0 vespene and has no gas income model.
 *       Train commands for Stalkers (50 gas) and Immortals (100 gas) are rejected, creating a
 *       growing divergence proportional to the number of gas units trained. See #148.</li>
 * </ul>
 *
 * <p>The timing formula fix (#149) corrected {@code trainTimeInLoops} from float literals
 * (e.g. 268.8) to empirically calibrated integers (PROBE=272, ZEALOT=618, STALKER=698),
 * moving {@code firstUnitDivergenceTick} from 86 to 150. The remaining divergence is
 * entirely from gas units and will be eliminated by #148.
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

        assertThat(report.summary().firstUnitDivergenceTick())
            .as("Training-time fix (#149) moved first divergence from 86 to 150. "
                + "Remaining cause is gas units (#148 — no vespene model). "
                + "First divergence was at tick %d.\n%s",
                report.summary().firstUnitDivergenceTick(), report.renderReport())
            .isGreaterThanOrEqualTo(145);

        assertThat(report.summary().maxUnitDelta())
            .as("Max unit delta is driven by gas units rejected due to 0 vespene (#148). "
                + "Will be 0 once #148 adds vespene income. Max was %d.\n%s",
                report.summary().maxUnitDelta(), report.renderReport())
            .isLessThanOrEqualTo(15);
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
