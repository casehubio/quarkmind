package io.quarkmind.sc2.emulated;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Point2d;
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
import java.util.EnumSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Refs #586
 */
@Tag("report")
class GasHarvestCalibrationTest {

    private static final Point2d BASE_POS               = new Point2d(50, 50);
    private static final Point2d GAS_POS                = new Point2d(55, 50);
    private static final String  BASE_TAG               = "base-1";
    private static final String  GAS_TAG                = "gas-1";
    private static final int     TICKS_PER_MINUTE_INT   =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final double  TICKS_PER_MINUTE       =
            60.0 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK;
    private static final Path    ORACLE_DIR             = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int[]   CHECKPOINTS_MIN        = {1, 3, 5, 7};
    private static final int     TICK_LIMIT             = TICKS_PER_MINUTE_INT * 8;
    private static final float   GAS_PER_WORKER_PER_MIN = 38.0f;

    @Test
    void gasRateSweep() {
        double[] durations = {5, 10, 15, 20, 25, 28, 30, 31.696, 35, 40, 45, 50};

        System.out.println("=== Gas Harvest Duration Sweep: Income per Geyser (5-unit distance) ===\n");
        System.out.printf("%-10s | %-12s | %-12s | %-12s | %-7s%n",
                          "Duration", "1w gas/min", "2w gas/min", "3w gas/min", "Seconds");
        System.out.println("-".repeat(65));

        for (double duration : durations) {
            SimulatedWorker.setGasHarvestDurationLoops(duration);
            try {
                double rate1   = measureGasRate(1);
                double rate2   = measureGasRate(2);
                double rate3   = measureGasRate(3);
                double seconds = duration / SC2Data.GAME_LOOPS_PER_SECOND;

                System.out.printf("%-10.1f | %-12.1f | %-12.1f | %-12.1f | %-7.2f%n",
                                  duration, rate1, rate2, rate3, seconds);
            } finally {
                SimulatedWorker.resetGasHarvestDuration();
            }
        }

        System.out.println("\n=== SC2 Reference Values ===");
        System.out.println("3 workers per geyser: ~170 gas/min (ideal)");
        System.out.println("Gas cargo: 4 per trip");
    }

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) {return false;}
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {return false;}
    }

    @Test
    @EnabledIf("oracleExists")
    void oracleGasMAPESweep() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        double[] durations = {8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 31.696, 35, 40, 45};

        System.out.println("=== Oracle MAPE Sweep: vespeneCollectionRate ===\n");
        System.out.printf("%-10s", "Duration");
        for (int cp : CHECKPOINTS_MIN) {
            System.out.printf(" | %-8s", cp + "min");
        }
        System.out.println(" | Best CP");
        System.out.println("-".repeat(70));

        for (double duration : durations) {
            SimulatedWorker.setGasHarvestDurationLoops(duration);
            try {
                double[] mapes = runGasOracleMAPE(replays);
                System.out.printf("%-10.3f", duration);
                double bestMape = Double.MAX_VALUE;
                for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
                    System.out.printf(" | %-7.1f%%", mapes[i]);
                    bestMape = Math.min(bestMape, mapes[i]);
                }
                System.out.printf(" | %.1f%%", bestMape);
                System.out.println();
            } finally {
                SimulatedWorker.resetGasHarvestDuration();
            }
        }

        System.out.println("\n=== Full Economy MAPE at Select Durations (7-min checkpoint) ===\n");
        double[] detailDurations = {15, 22, 25, 31.696, 40};
        String[] econFields = {"mineralsCurrent", "vespeneCurrent",
                               "mineralsCollRate", "vespeneCollRate",
                               "foodMade", "foodUsed", "workersActive",
                               "minUsedArmy", "minUsedEcon", "minUsedTech",
                               "vesUsedArmy", "vesUsedEcon", "vesUsedTech"};

        System.out.printf("%-18s", "Field");
        for (double d : detailDurations) {
            System.out.printf(" | %-8.1f", d);
        }
        System.out.println();
        System.out.println("-".repeat(80));

        double[][] allEconMapes = new double[detailDurations.length][];
        for (int di = 0; di < detailDurations.length; di++) {
            SimulatedWorker.setGasHarvestDurationLoops(detailDurations[di]);
            try {
                allEconMapes[di] = runFullEconomyMAPE(replays, 7);
            } finally {
                SimulatedWorker.resetGasHarvestDuration();
            }
        }

        for (int fi = 0; fi < econFields.length; fi++) {
            System.out.printf("%-18s", econFields[fi]);
            for (int di = 0; di < detailDurations.length; di++) {
                System.out.printf(" | %-7.1f%%", allEconMapes[di][fi]);
            }
            System.out.println();
        }
    }

    private double[] runGasOracleMAPE(List<Path> replays) {
        double[][] accum = new double[CHECKPOINTS_MIN.length][3];

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    List<PlayerEconomyStats[]> snapshots = runStandaloneEconomy(replayPath, replay, playerId);

                    for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
                        int tickIndex = CHECKPOINTS_MIN[ci] * TICKS_PER_MINUTE_INT - 1;
                        if (tickIndex >= snapshots.size()) {continue;}
                        PlayerEconomyStats[] pair = snapshots.get(tickIndex);
                        if (pair == null) {continue;}
                        double gt = pair[0].vespeneCollectionRate();
                        double em = pair[1].vespeneCollectionRate();
                        accum[ci][0] += gt;
                        accum[ci][1] += em;
                        accum[ci][2] += Math.abs(gt - em);
                    }
                } catch (Exception ignored) {}
            }
        }

        double[] mapes = new double[CHECKPOINTS_MIN.length];
        for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
            mapes[ci] = accum[ci][0] != 0 ? accum[ci][2] / Math.abs(accum[ci][0]) * 100.0 : 0;
        }
        return mapes;
    }

    private double[] runFullEconomyMAPE(List<Path> replays, int checkpointMin) {
        double[][] accum = new double[13][3];

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    List<PlayerEconomyStats[]> snapshots = runStandaloneEconomy(replayPath, replay, playerId);

                    int tickIndex = checkpointMin * TICKS_PER_MINUTE_INT - 1;
                    if (tickIndex >= snapshots.size()) {continue;}
                    PlayerEconomyStats[] pair = snapshots.get(tickIndex);
                    if (pair == null) {continue;}

                    PlayerEconomyStats gt = pair[0];
                    PlayerEconomyStats em = pair[1];
                    accumulateField(accum, 0, gt.mineralsCurrent(), em.mineralsCurrent());
                    accumulateField(accum, 1, gt.vespeneCurrent(), em.vespeneCurrent());
                    accumulateField(accum, 2, gt.mineralsCollectionRate(), em.mineralsCollectionRate());
                    accumulateField(accum, 3, gt.vespeneCollectionRate(), em.vespeneCollectionRate());
                    accumulateField(accum, 4, gt.foodMade(), em.foodMade());
                    accumulateField(accum, 5, gt.foodUsed(), em.foodUsed());
                    accumulateField(accum, 6, gt.workersActiveCount(), em.workersActiveCount());
                    accumulateField(accum, 7, gt.mineralsUsedCurrentArmy(), em.mineralsUsedCurrentArmy());
                    accumulateField(accum, 8, gt.mineralsUsedCurrentEconomy(), em.mineralsUsedCurrentEconomy());
                    accumulateField(accum, 9, gt.mineralsUsedCurrentTechnology(), em.mineralsUsedCurrentTechnology());
                    accumulateField(accum, 10, gt.vespeneUsedCurrentArmy(), em.vespeneUsedCurrentArmy());
                    accumulateField(accum, 11, gt.vespeneUsedCurrentEconomy(), em.vespeneUsedCurrentEconomy());
                    accumulateField(accum, 12, gt.vespeneUsedCurrentTechnology(), em.vespeneUsedCurrentTechnology());
                } catch (Exception ignored) {}
            }
        }

        double[] mapes = new double[13];
        for (int i = 0; i < 13; i++) {
            mapes[i] = accum[i][0] != 0 ? accum[i][2] / Math.abs(accum[i][0]) * 100.0 : 0;
        }
        return mapes;
    }

    private List<PlayerEconomyStats[]> runStandaloneEconomy(
            Path replayPath, Replay replay, int playerId) {

        ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
        List<TimedIntent>   intents     = TrackerIntentExtractor.extract(replayPath, playerId);
        Race                race        = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
        groundTruth.reset();
        emulated.setMineralPatchResources(groundTruth.getMineralPatches());
        emulated.resetForHarness();

        int                        cursor    = 0;
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

            int gtGasRate           = gt.playerEconomy().vespeneCollectionRate();
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

    private static void accumulateField(double[][] accum, int index, double gtVal, double emVal) {
        accum[index][0] += gtVal;
        accum[index][1] += emVal;
        accum[index][2] += Math.abs(gtVal - emVal);
    }

    private double measureGasRate(int gasWorkerCount) {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);

        List<String> initial = IntStream.rangeClosed(1, 12)
                                        .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(initial, BASE_POS, BASE_TAG);

        sim.onGasBuildingCompleted(GAS_TAG, GAS_POS);
        sim.syncTotalGasWorkers(gasWorkerCount);

        int tickCount = 0;
        int warmup    = 30;
        for (int t = 0; t < warmup; t++) {
            sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK);
        }

        double income  = 0;
        int    measure = 400;
        for (int t = 0; t < measure; t++) {
            income += sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK).vespeneIncome();
        }

        return income / measure * TICKS_PER_MINUTE;
    }
}
