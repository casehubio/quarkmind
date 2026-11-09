package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Calibrates per-race mineral mining efficiency factor by sweeping values
 * and measuring mineralsCurrent MAPE at 5-min and 7-min checkpoints.
 *
 * Uses linear approximation: runs each replay once with efficiency=1.0,
 * records cumulative mineral income, then computes adjusted minerals
 * for each candidate factor without re-running.
 *
 * Refs #566, #534
 */
@Tag("diagnostic")
class MiningEfficiencyCalibrationTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    private static final double[] CANDIDATE_FACTORS = {
        0.85, 0.86, 0.87, 0.88, 0.89,
        0.90, 0.91, 0.92, 0.93, 0.94,
        0.95, 0.96, 0.97, 0.98, 0.99,
        1.00, 1.02, 1.04, 1.06, 1.08,
        1.10, 1.12, 1.14, 1.16, 1.18,
        1.20, 1.22, 1.24, 1.26, 1.28, 1.30
    };

    record CheckpointData(Race race, int checkpoint,
                          double gtMinerals, double emMinerals,
                          double cumulativeMineralIncome,
                          double gtMineralRate, double emMineralRate) {}

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void calibrateMiningEfficiency() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        List<CheckpointData> allData = new ArrayList<>();

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

                    List<DivergenceReport.TickSnapshot> snapshots =
                        StandaloneEmulatedGameBaselineTest.runStandalone(
                            groundTruth, intents, TICK_LIMIT, race);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= snapshots.size()) continue;
                        var snap = snapshots.get(tickIndex);
                        if (snap.groundTruthEconomy() == null || snap.emulatedEconomy() == null) continue;

                        double gtMinerals = snap.groundTruthEconomy().mineralsCurrent();
                        double emMinerals = snap.emulatedEconomy().mineralsCurrent();
                        double totalSpending = snap.emulatedEconomy().mineralsUsedCurrentArmy()
                            + snap.emulatedEconomy().mineralsUsedCurrentEconomy()
                            + snap.emulatedEconomy().mineralsUsedCurrentTechnology();
                        double cumulativeIncome = emMinerals + totalSpending - SC2Data.INITIAL_MINERALS;
                        double gtRate = snap.groundTruthEconomy().mineralsCollectionRate();
                        double emRate = snap.emulatedEconomy().mineralsCollectionRate();

                        allData.add(new CheckpointData(race, cp, gtMinerals, emMinerals,
                            cumulativeIncome, gtRate, emRate));
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("=== Mining Efficiency Calibration ===\n");
        System.out.printf("Total checkpoint samples: %d%n%n", allData.size());

        for (Race race : Race.values()) {
            List<CheckpointData> raceData = allData.stream()
                .filter(d -> d.race() == race).toList();
            if (raceData.isEmpty()) continue;

            System.out.printf("--- %s (%d samples) ---%n", race, raceData.size());
            System.out.printf("%-8s", "Factor");
            for (int cp : CHECKPOINTS_MIN) {
                System.out.printf("  %d-min MAPE", cp);
            }
            System.out.printf("  Combined%n");

            double bestCombinedMape = Double.MAX_VALUE;
            double bestFactor = 1.0;

            for (double factor : CANDIDATE_FACTORS) {
                System.out.printf("%-8.2f", factor);
                double combinedAbsErr = 0;
                double combinedGt = 0;

                for (int cp : CHECKPOINTS_MIN) {
                    List<CheckpointData> cpData = raceData.stream()
                        .filter(d -> d.checkpoint() == cp).toList();
                    double absErrSum = 0;
                    double gtSum = 0;
                    for (CheckpointData d : cpData) {
                        double adjustedEm = d.emMinerals() - (1.0 - factor) * d.cumulativeMineralIncome();
                        absErrSum += Math.abs(d.gtMinerals() - adjustedEm);
                        gtSum += Math.abs(d.gtMinerals());
                    }
                    double mape = gtSum > 0 ? absErrSum / gtSum * 100.0 : 0;
                    System.out.printf("  %8.1f%%", mape);
                    combinedAbsErr += absErrSum;
                    combinedGt += gtSum;
                }

                double combinedMape = combinedGt > 0 ? combinedAbsErr / combinedGt * 100.0 : 0;
                System.out.printf("  %8.1f%%%n", combinedMape);

                if (combinedMape < bestCombinedMape) {
                    bestCombinedMape = combinedMape;
                    bestFactor = factor;
                }
            }
            System.out.printf(">>> BALANCE OPTIMAL: %.2f (combined MAPE: %.1f%%)%n%n", bestFactor, bestCombinedMape);

            System.out.printf("--- %s RATE MAPE (%d samples) ---%n", race, raceData.size());
            System.out.printf("%-8s", "Factor");
            for (int cp : CHECKPOINTS_MIN) {
                System.out.printf("  %d-min MAPE", cp);
            }
            System.out.printf("  Combined%n");

            double bestRateMape = Double.MAX_VALUE;
            double bestRateFactor = 1.0;

            for (double factor : CANDIDATE_FACTORS) {
                System.out.printf("%-8.2f", factor);
                double combinedAbsErr2 = 0;
                double combinedGt2 = 0;

                for (int cp : CHECKPOINTS_MIN) {
                    final int cpFinal = cp;
                    List<CheckpointData> cpData = raceData.stream()
                        .filter(d -> d.checkpoint() == cpFinal).toList();
                    double absErrSum = 0;
                    double gtSum = 0;
                    for (CheckpointData d : cpData) {
                        double adjustedRate = d.emMineralRate() * factor;
                        absErrSum += Math.abs(d.gtMineralRate() - adjustedRate);
                        gtSum += Math.abs(d.gtMineralRate());
                    }
                    double mape = gtSum > 0 ? absErrSum / gtSum * 100.0 : 0;
                    System.out.printf("  %8.1f%%", mape);
                    combinedAbsErr2 += absErrSum;
                    combinedGt2 += gtSum;
                }

                double combinedMape2 = combinedGt2 > 0 ? combinedAbsErr2 / combinedGt2 * 100.0 : 0;
                System.out.printf("  %8.1f%%%n", combinedMape2);

                if (combinedMape2 < bestRateMape) {
                    bestRateMape = combinedMape2;
                    bestRateFactor = factor;
                }
            }
            System.out.printf(">>> RATE OPTIMAL: %.2f (combined rate MAPE: %.1f%%)%n%n", bestRateFactor, bestRateMape);
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void calibrateGasEfficiency() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        record GasCheckpointData(Race race, int checkpoint,
                                 double gtGasRate, double emGasRate) {}

        List<GasCheckpointData> allData = new ArrayList<>();

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent>   intents     = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race                race        = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    List<DivergenceReport.TickSnapshot> snapshots =
                            StandaloneEmulatedGameBaselineTest.runStandalone(
                                    groundTruth, intents, TICK_LIMIT, race);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= snapshots.size()) {continue;}
                        var snap = snapshots.get(tickIndex);
                        if (snap.groundTruthEconomy() == null || snap.emulatedEconomy() == null) {continue;}

                        double gtGasRate = snap.groundTruthEconomy().vespeneCollectionRate();
                        double emGasRate = snap.emulatedEconomy().vespeneCollectionRate();

                        allData.add(new GasCheckpointData(race, cp, gtGasRate, emGasRate));
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("=== Gas Efficiency Calibration (Rate MAPE) ===\n");
        System.out.printf("Total checkpoint samples: %d%n%n", allData.size());

        for (Race race : Race.values()) {
            List<GasCheckpointData> raceData = allData.stream()
                                                      .filter(d -> d.race() == race).toList();
            if (raceData.isEmpty()) {continue;}

            System.out.printf("--- %s GAS RATE MAPE (%d samples) ---%n", race, raceData.size());
            System.out.printf("%-8s", "Factor");
            for (int cp : CHECKPOINTS_MIN) {
                System.out.printf("  %d-min MAPE", cp);
            }
            System.out.printf("  Combined%n");

            double bestRateMape   = Double.MAX_VALUE;
            double bestRateFactor = 1.0;

            for (double factor : CANDIDATE_FACTORS) {
                System.out.printf("%-8.2f", factor);
                double combinedAbsErr = 0;
                double combinedGt     = 0;

                for (int cp : CHECKPOINTS_MIN) {
                    final int cpFinal = cp;
                    List<GasCheckpointData> cpData = raceData.stream()
                                                             .filter(d -> d.checkpoint() == cpFinal).toList();
                    double absErrSum = 0;
                    double gtSum     = 0;
                    for (GasCheckpointData d : cpData) {
                        double adjustedRate = d.emGasRate() * factor;
                        absErrSum += Math.abs(d.gtGasRate() - adjustedRate);
                        gtSum += Math.abs(d.gtGasRate());
                    }
                    double mape = gtSum > 0 ? absErrSum / gtSum * 100.0 : 0;
                    System.out.printf("  %8.1f%%", mape);
                    combinedAbsErr += absErrSum;
                    combinedGt += gtSum;
                }

                double combinedMape = combinedGt > 0 ? combinedAbsErr / combinedGt * 100.0 : 0;
                System.out.printf("  %8.1f%%%n", combinedMape);

                if (combinedMape < bestRateMape) {
                    bestRateMape   = combinedMape;
                    bestRateFactor = factor;
                }
            }
            System.out.printf(">>> GAS RATE OPTIMAL: %.2f (combined gas rate MAPE: %.1f%%)%n%n",
                              bestRateFactor, bestRateMape);
        }
    }

}
