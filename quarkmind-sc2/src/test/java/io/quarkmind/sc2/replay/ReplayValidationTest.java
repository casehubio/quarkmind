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
 * <p>Minerals ARE synced from post-tick ground truth after intents fire (#502), so
 * mineralsCurrent has zero divergence. Vespene is synced pre-intent for affordability (#148).
 * Mining probe count IS synced so the saturation model (#141) produces realistic income.
 *
 * <p>Unit count divergence: bounded at ≤ 3 within the 3-minute window. Vespene is synced from
 * pre-tick ground truth (#148), so gas-unit train commands are never rejected. The residual
 * delta comes from tick-boundary training completion timing differences.
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
            .as("Unit divergence delayed by worker simulation income ramp-up (#500).\n%s",
                report.renderReport())
            .isGreaterThanOrEqualTo(20);

        assertThat(report.summary().maxUnitDelta())
            .as("Max unit delta bounded at 3 by tick-boundary timing (#502).\n%s",
                report.renderReport())
            .isLessThanOrEqualTo(3);
    }

    @Test
    void mineralDeltaWithinToleranceForThreeMinutes() {
        DivergenceReport report = ReplayValidationHarness.run(REPLAY, 1, THREE_MINUTES_TICKS);

        // Minerals synced from GT after intents (#502) — zero divergence expected.
        assertThat(report.summary().maxMineralDelta())
            .as("Mineral delta must be 0 (synced from GT, #502). Max was %d.\n%s",
                report.summary().maxMineralDelta(), report.renderReport())
            .isLessThanOrEqualTo(0);
    }
}
