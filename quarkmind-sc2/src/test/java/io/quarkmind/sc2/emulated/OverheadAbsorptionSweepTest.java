package io.quarkmind.sc2.emulated;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.replay.TrackerIntentExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Refs #592
 */
@Tag("diagnostic")
class OverheadAbsorptionSweepTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE_INT =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE_INT * 8;
    private static final float GAS_PER_WORKER_PER_MIN = 38.0f;

    record SweepResult(double deposit, double mining, double gas,
                       double minBalMape5, double vesBalMape5,
                       double minRateMape5, double vesRateMape5,
                       double minBalMape7, double vesBalMape7,
                       double minRateMape7, double vesRateMape7) {

        double combinedRate5() {
            return (minRateMape5 + vesRateMape5) / 2.0;
        }

        double combinedRate7() {
            return (minRateMape7 + vesRateMape7) / 2.0;
        }

        double combinedBalance7() {
            return (minBalMape7 + vesBalMape7) / 2.0;
        }
    }

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void jointOverheadSweep() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().toList();
        }

        double[] depositValues = {0, 1, 2, 3, 4, 5};
        double[] miningValues  = {8, 9, 10, 11, 12, 13, 14, 15};
        double[] gasValues     = {16, 18, 20, 22, 24, 26};

        int total = depositValues.length * miningValues.length * gasValues.length;
        System.out.printf("=== 3D Overhead Absorption Sweep (%d replays, %d combinations) ===%n%n",
                replays.size(), total);
        System.out.printf("Deposit: %s%n", formatArray(depositValues));
        System.out.printf("Mining:  %s%n", formatArray(miningValues));
        System.out.printf("Gas:     %s%n%n", formatArray(gasValues));

        System.out.printf("%-5s %-6s %-5s | %-8s %-8s %-8s %-8s | %-8s %-8s %-8s %-8s | %-8s %-8s%n",
                "Dep", "Mining", "Gas",
                "mBal5", "vBal5", "mRate5", "vRate5",
                "mBal7", "vBal7", "mRate7", "vRate7",
                "cRate5", "cRate7");
        System.out.println("-".repeat(125));

        List<SweepResult> results = new ArrayList<>(total);
        int done = 0;

        for (double deposit : depositValues) {
            for (double mining : miningValues) {
                for (double gas : gasValues) {
                    SimulatedWorker.setDepositDurationLoops(deposit);
                    SimulatedWorker.setMiningDurationLoops(mining);
                    SimulatedWorker.setGasHarvestDurationLoops(gas);
                    try {
                        SweepResult r = runSweepPoint(replays, deposit, mining, gas);
                        results.add(r);

                        System.out.printf("%-5.0f %-6.0f %-5.0f | %-7.1f%% %-7.1f%% %-7.1f%% %-7.1f%% | %-7.1f%% %-7.1f%% %-7.1f%% %-7.1f%% | %-7.1f%% %-7.1f%%%n",
                                deposit, mining, gas,
                                r.minBalMape5, r.vesBalMape5, r.minRateMape5, r.vesRateMape5,
                                r.minBalMape7, r.vesBalMape7, r.minRateMape7, r.vesRateMape7,
                                r.combinedRate5(), r.combinedRate7());
                    } finally {
                        SimulatedWorker.resetToDefaults();
                    }
                    done++;
                    if (done % 48 == 0) {
                        System.out.printf("  [progress: %d/%d]%n", done, total);
                    }
                }
            }
        }

        printRankings(results);
    }

    private SweepResult runSweepPoint(List<Path> replays, double deposit, double mining, double gas) {
        double[][] accum5 = new double[4][3];
        double[][] accum7 = new double[4][3];

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    List<PlayerEconomyStats[]> snapshots = runStandaloneEconomy(replayPath, replay, playerId);
                    accumulateCheckpoint(accum5, snapshots, 5);
                    accumulateCheckpoint(accum7, snapshots, 7);
                } catch (Exception ignored) {}
            }
        }

        return new SweepResult(deposit, mining, gas,
                mape(accum5[0]), mape(accum5[1]), mape(accum5[2]), mape(accum5[3]),
                mape(accum7[0]), mape(accum7[1]), mape(accum7[2]), mape(accum7[3]));
    }

    private void accumulateCheckpoint(double[][] accum, List<PlayerEconomyStats[]> snapshots, int checkpointMin) {
        int tickIndex = checkpointMin * TICKS_PER_MINUTE_INT - 1;
        if (tickIndex >= snapshots.size()) return;
        PlayerEconomyStats[] pair = snapshots.get(tickIndex);
        if (pair == null) return;

        PlayerEconomyStats gt = pair[0];
        PlayerEconomyStats em = pair[1];
        accumulateField(accum, 0, gt.mineralsCurrent(), em.mineralsCurrent());
        accumulateField(accum, 1, gt.vespeneCurrent(), em.vespeneCurrent());
        accumulateField(accum, 2, gt.mineralsCollectionRate(), em.mineralsCollectionRate());
        accumulateField(accum, 3, gt.vespeneCollectionRate(), em.vespeneCollectionRate());
    }

    private List<PlayerEconomyStats[]> runStandaloneEconomy(
            Path replayPath, Replay replay, int playerId) {

        ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
        List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
        Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
        groundTruth.reset();
        emulated.setMineralPatchResources(groundTruth.getMineralPatches());
        emulated.resetForHarness();

        int cursor = 0;
        List<PlayerEconomyStats[]> snapshots = new ArrayList<>(Math.min(TICK_LIMIT, 10000));

        for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

            emulated.tick();
            groundTruth.tick();

            GameState gt = groundTruth.snapshot();

            emulated.processDeferredTrainIntents();
            emulated.processDeferredResearchIntents();

            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                emulated.applyIntent(intents.get(cursor));
                cursor++;
            }

            int gtGasRate = gt.playerEconomy().vespeneCollectionRate();
            int estimatedGasWorkers = gtGasRate > 0 ? Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN)) : 0;
            emulated.syncTotalGasWorkers(estimatedGasWorkers);

            GameState em = emulated.snapshot();

            if (gt.playerEconomy() != null && em.playerEconomy() != null) {
                snapshots.add(new PlayerEconomyStats[]{gt.playerEconomy(), em.playerEconomy()});
            } else {
                snapshots.add(null);
            }
        }

        return snapshots;
    }

    private void printRankings(List<SweepResult> results) {
        SweepResult current = results.stream()
                .filter(r -> r.deposit == 0 && r.mining == 12 && r.gas == 22)
                .findFirst().orElse(null);

        System.out.println("\n=== Top 10 by Combined Rate MAPE (7-min) ===\n");
        System.out.printf("%-5s %-6s %-5s | %-8s %-8s | %-10s%n",
                "Dep", "Mining", "Gas", "cRate7", "cBal7", "vs current");
        System.out.println("-".repeat(55));

        results.stream()
                .sorted(Comparator.comparingDouble(SweepResult::combinedRate7))
                .limit(10)
                .forEach(r -> {
                    String delta = current != null
                            ? String.format("%+.1f%%", r.combinedRate7() - current.combinedRate7())
                            : "—";
                    System.out.printf("%-5.0f %-6.0f %-5.0f | %-7.1f%% %-7.1f%% | %-10s%n",
                            r.deposit, r.mining, r.gas,
                            r.combinedRate7(), r.combinedBalance7(), delta);
                });

        System.out.println("\n=== Top 10 by Combined Balance MAPE (7-min) ===\n");
        System.out.printf("%-5s %-6s %-5s | %-8s %-8s | %-10s%n",
                "Dep", "Mining", "Gas", "cBal7", "cRate7", "vs current");
        System.out.println("-".repeat(55));

        results.stream()
                .sorted(Comparator.comparingDouble(SweepResult::combinedBalance7))
                .limit(10)
                .forEach(r -> {
                    String delta = current != null
                            ? String.format("%+.1f%%", r.combinedBalance7() - current.combinedBalance7())
                            : "—";
                    System.out.printf("%-5.0f %-6.0f %-5.0f | %-7.1f%% %-7.1f%% | %-10s%n",
                            r.deposit, r.mining, r.gas,
                            r.combinedBalance7(), r.combinedRate7(), delta);
                });

        if (current != null) {
            System.out.printf("%n=== Current Defaults (deposit=0, mining=12, gas=22) ===%n");
            System.out.printf("Combined rate MAPE (7-min):    %.1f%%%n", current.combinedRate7());
            System.out.printf("Combined balance MAPE (7-min): %.1f%%%n", current.combinedBalance7());
            System.out.printf("Combined rate MAPE (5-min):    %.1f%%%n", current.combinedRate5());

            SweepResult bestRate = results.stream()
                    .min(Comparator.comparingDouble(SweepResult::combinedRate7)).orElse(current);
            SweepResult bestBalance = results.stream()
                    .min(Comparator.comparingDouble(SweepResult::combinedBalance7)).orElse(current);

            System.out.printf("%nBest rate triple:    deposit=%.0f mining=%.0f gas=%.0f → %.1f%% (delta: %+.1f%%)%n",
                    bestRate.deposit, bestRate.mining, bestRate.gas, bestRate.combinedRate7(),
                    bestRate.combinedRate7() - current.combinedRate7());
            System.out.printf("Best balance triple: deposit=%.0f mining=%.0f gas=%.0f → %.1f%% (delta: %+.1f%%)%n",
                    bestBalance.deposit, bestBalance.mining, bestBalance.gas, bestBalance.combinedBalance7(),
                    bestBalance.combinedBalance7() - current.combinedBalance7());

            boolean rateImproved = bestRate.combinedRate7() < current.combinedRate7() - 0.5;
            boolean balanceImproved = bestBalance.combinedBalance7() < current.combinedBalance7() - 0.5;

            System.out.printf("%n=== Conclusion ===%n");
            if (!rateImproved && !balanceImproved) {
                System.out.println("No triple improves on (0, 12, 22) by >0.5%% in either metric.");
                System.out.println("The lumped-constant model is at its accuracy ceiling for the current architecture.");
            } else {
                if (rateImproved) {
                    System.out.printf("Rate improvement found: (%.0f, %.0f, %.0f) beats current by %.1f%% — investigate further.%n",
                            bestRate.deposit, bestRate.mining, bestRate.gas,
                            current.combinedRate7() - bestRate.combinedRate7());
                }
                if (balanceImproved) {
                    System.out.printf("Balance improvement found: (%.0f, %.0f, %.0f) beats current by %.1f%% — investigate further.%n",
                            bestBalance.deposit, bestBalance.mining, bestBalance.gas,
                            current.combinedBalance7() - bestBalance.combinedBalance7());
                }
            }
        }
    }

    private static double mape(double[] accum) {
        return accum[0] != 0 ? accum[2] / Math.abs(accum[0]) * 100.0 : 0;
    }

    private static void accumulateField(double[][] accum, int index, double gtVal, double emVal) {
        accum[index][0] += gtVal;
        accum[index][1] += emVal;
        accum[index][2] += Math.abs(gtVal - emVal);
    }

    private static String formatArray(double[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.0f", arr[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
