package io.quarkmind.sc2.replay;

import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import io.quarkmind.sc2.intent.TimedIntent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration diagnostic for mining income model constants.
 * Extracts GT vs Em mineral/vespene collection rates at each checkpoint,
 * computes per-worker income rates, and derives optimal tier constants.
 */
class MiningIncomeCalibrationTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @Tag("diagnostic")
    @EnabledIf("oracleExists")
    void calibrateMiningIncomeRates() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        // Per-checkpoint accumulators: [gtMineralRate, emMineralRate, gtVespeneRate, emVespeneRate, gtWorkers, count]
        Map<Integer, double[]> accum = new TreeMap<>();
        for (int cp : CHECKPOINTS_MIN) {
            accum.put(cp, new double[6]);
        }
        int totalPlayerRuns = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    DivergenceReport report = ReplayValidationHarness.run(
                        groundTruth, intents, TICK_LIMIT, race);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= report.ticks().size()) continue;
                        var snap = report.ticks().get(tickIndex);
                        PlayerEconomyStats gt = snap.groundTruthEconomy();
                        PlayerEconomyStats em = snap.emulatedEconomy();
                        if (gt == null || em == null) continue;

                        double[] a = accum.get(cp);
                        a[0] += gt.mineralsCollectionRate();
                        a[1] += em.mineralsCollectionRate();
                        a[2] += gt.vespeneCollectionRate();
                        a[3] += em.vespeneCollectionRate();
                        a[4] += gt.workersActiveCount();
                        a[5]++;
                    }
                    totalPlayerRuns++;
                } catch (Exception e) { /* skip failed replays */ }
            }
        }

        System.out.println("=== Mining Income Calibration Report ===");
        System.out.printf("Replays: %d, Player runs: %d%n%n", replays.size(), totalPlayerRuns);

        System.out.println("Current SC2Data constants:");
        System.out.printf("  MINERAL_TIER_RATES (min/min/worker): %.1f / %.1f / %.1f%n",
            SC2Data.MINERAL_TIER_RATES_PER_TICK[0] / SC2Data.LOOPS_PER_TICK * SC2Data.GAME_LOOPS_PER_SECOND * 60,
            SC2Data.MINERAL_TIER_RATES_PER_TICK[1] / SC2Data.LOOPS_PER_TICK * SC2Data.GAME_LOOPS_PER_SECOND * 60,
            SC2Data.MINERAL_TIER_RATES_PER_TICK[2] / SC2Data.LOOPS_PER_TICK * SC2Data.GAME_LOOPS_PER_SECOND * 60);
        System.out.printf("  GAS_TIER_RATES (gas/min/worker): %.1f / %.1f / %.1f%n%n",
            SC2Data.GAS_TIER_RATES_PER_TICK[0] / SC2Data.LOOPS_PER_TICK * SC2Data.GAME_LOOPS_PER_SECOND * 60,
            SC2Data.GAS_TIER_RATES_PER_TICK[1] / SC2Data.LOOPS_PER_TICK * SC2Data.GAME_LOOPS_PER_SECOND * 60,
            SC2Data.GAS_TIER_RATES_PER_TICK[2] / SC2Data.LOOPS_PER_TICK * SC2Data.GAME_LOOPS_PER_SECOND * 60);

        System.out.printf("%-6s  %10s  %10s  %7s  %10s  %10s  %7s  %8s  %6s%n",
            "Chkpt", "GT MinRate", "Em MinRate", "Ratio",
            "GT GasRate", "Em GasRate", "Ratio", "Workers", "Runs");
        System.out.println("-".repeat(90));

        for (int cp : CHECKPOINTS_MIN) {
            double[] a = accum.get(cp);
            int runs = (int) a[5];
            if (runs == 0) continue;

            double avgGtMinRate = a[0] / runs;
            double avgEmMinRate = a[1] / runs;
            double minRatio = avgEmMinRate > 0 ? avgGtMinRate / avgEmMinRate : Double.NaN;

            double avgGtGasRate = a[2] / runs;
            double avgEmGasRate = a[3] / runs;
            double gasRatio = avgEmGasRate > 0 ? avgGtGasRate / avgEmGasRate : Double.NaN;

            double avgWorkers = a[4] / runs;

            System.out.printf("%-6d  %10.1f  %10.1f  %7.2f  %10.1f  %10.1f  %7.2f  %8.1f  %6d%n",
                cp, avgGtMinRate, avgEmMinRate, minRatio,
                avgGtGasRate, avgEmGasRate, gasRatio,
                avgWorkers, runs);
        }

        // Compute recommended scale factors from 5-min checkpoint (most representative)
        double[] a5 = accum.get(5);
        if (a5[5] > 0 && a5[1] > 0 && a5[3] > 0) {
            double mineralScale = a5[0] / a5[1];
            double gasScale = a5[2] / a5[3];

            System.out.printf("%n=== Recommended Scale Factors (from 5-min checkpoint) ===%n");
            System.out.printf("  Mineral tier rates: multiply by %.3f%n", mineralScale);
            System.out.printf("  Gas tier rates:     multiply by %.3f%n", gasScale);

            System.out.printf("%n=== Calibrated Constants ===%n");
            System.out.printf("  MINERAL_TIER_RATES (min/min/worker): %.1f / %.1f / %.1f%n",
                50.0 * mineralScale, 25.0 * mineralScale, 5.0 * mineralScale);
            System.out.printf("  GAS_TIER_RATES (gas/min/worker): %.1f / %.1f / %.1f%n",
                38.0 * gasScale, 38.0 * gasScale, 20.0 * gasScale);

            // Verify scale factor is reasonable (between 1.0 and 3.0)
            assertThat(mineralScale).as("mineral scale factor should be reasonable")
                .isBetween(1.0, 3.0);
            assertThat(gasScale).as("gas scale factor should be reasonable")
                .isBetween(1.0, 3.0);
        }
    }
}
