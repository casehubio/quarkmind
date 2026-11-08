package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibrates MULE income per tick from Terran oracle replays.
 * Tracks MULE birth/death events to count active MULEs at PlayerStats
 * checkpoints, then derives per-MULE mineral income from the GT rate
 * minus expected worker income.
 */
@Tag("diagnostic")
class MuleIncomeCalibrationTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int[] CHECKPOINTS_LOOPS = {
        3 * 60 * (int) SC2Data.GAME_LOOPS_PER_SECOND,
        5 * 60 * (int) SC2Data.GAME_LOOPS_PER_SECOND,
        7 * 60 * (int) SC2Data.GAME_LOOPS_PER_SECOND
    };
    private static final double TICKS_PER_MINUTE = 60.0 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK;
    private static final int PATCHES_PER_BASE = 8;
    private static final double[] MINERAL_TIERS = {
        77.7 / 60.0 * SC2Data.LOOPS_PER_TICK / SC2Data.GAME_LOOPS_PER_SECOND,
        38.8 / 60.0 * SC2Data.LOOPS_PER_TICK / SC2Data.GAME_LOOPS_PER_SECOND,
        7.8 / 60.0 * SC2Data.LOOPS_PER_TICK / SC2Data.GAME_LOOPS_PER_SECOND,
    };
    private static double approxMineralIncomePerTick(int workerCount) {
        double income = 0;
        for (int tier = 0; tier < MINERAL_TIERS.length; tier++) {
            int start = tier * PATCHES_PER_BASE;
            int inTier = Math.min(Math.max(workerCount - start, 0), PATCHES_PER_BASE);
            income += inTier * MINERAL_TIERS[tier];
        }
        return income;
    }

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    record MuleLifespan(int playerId, long birthLoop, long deathLoop) {
        boolean activeAt(long loop) { return loop >= birthLoop && loop < deathLoop; }
    }

    @Test
    @EnabledIf("oracleExists")
    void calibrateMuleIncomeFromTerranReplays() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        double sumMuleIncome = 0;
        int    sumMuleCount  = 0;
        int    terranPlayers = 0;
        int    checkpointsWithMules = 0;

        // Per-checkpoint accumulators for report
        Map<Integer, double[]> cpAccum = new HashMap<>();
        for (int cpLoop : CHECKPOINTS_LOOPS) {
            cpAccum.put(cpLoop, new double[]{0, 0, 0, 0}); // [gtRate, workerIncome, muleCount, samples]
        }

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null || replay.trackerEvents == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                var playerRace = replay.details.getPlayerList()[playerId - 1].getRace();
                if (playerRace != hu.scelight.sc2.rep.model.details.Race.TERRAN) continue;
                terranPlayers++;

                // Track MULE lifespans from tracker events
                List<MuleLifespan> mules = new ArrayList<>();
                Map<Long, Long> muleTagToBirth = new HashMap<>();

                // Also track gas building count and worker count from PlayerStats near checkpoints
                Map<Long, int[]> playerStatsAtLoop = new HashMap<>(); // loop -> [minRate, workers, gasBldgs]

                for (Event raw : replay.trackerEvents.getEvents()) {
                    int id = raw.getId();
                    if (id == ITrackerEvents.ID_UNIT_BORN) {
                        if (!(raw instanceof IBaseUnitEvent ue)) continue;
                        Integer pid = ue.getControlPlayerId();
                        if (pid == null || pid != playerId) continue;
                        String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                        if ("MULE".equalsIgnoreCase(typeName)) {
                            int tagIdx = ue.getUnitTagIndex() != null ? ue.getUnitTagIndex() : 0;
                            int tagRec = ue.getUnitTagRecycle() != null ? ue.getUnitTagRecycle() : 0;
                            long compositeTag = ((long) tagIdx << 32) | (tagRec & 0xFFFFFFFFL);
                            muleTagToBirth.put(compositeTag, (long) ue.getLoop());
                        }
                    } else if (id == ITrackerEvents.ID_UNIT_DIED) {
                        Integer tagIdx = raw.get("unitTagIndex");
                        Integer tagRec = raw.get("unitTagRecycle");
                        if (tagIdx == null || tagRec == null) continue;
                        long compositeTag = ((long) tagIdx << 32) | (tagRec & 0xFFFFFFFFL);
                        Long birthLoop = muleTagToBirth.remove(compositeTag);
                        if (birthLoop != null) {
                            mules.add(new MuleLifespan(playerId, birthLoop, raw.getLoop()));
                        }
                    } else if (id == ITrackerEvents.ID_PLAYER_STATS) {
                        if (!(raw instanceof IPlayerStatsEvent ps)) continue;
                        if (ps.getPlayerId() == null || ps.getPlayerId() != playerId) continue;
                        long loop = ps.getLoop();
                        int minRate = ps.getMinsCollRate() != null ? ps.getMinsCollRate() : 0;
                        int workers = ps.getWorkersActiveCount() != null ? ps.getWorkersActiveCount() : 0;
                        playerStatsAtLoop.put(loop, new int[]{minRate, workers});
                    }
                }

                // MULEs still alive at end of replay — set death to max
                for (var entry : muleTagToBirth.entrySet()) {
                    mules.add(new MuleLifespan(playerId, entry.getValue(), Long.MAX_VALUE));
                }

                // At each checkpoint, find nearest PlayerStats and count active MULEs
                for (int cpLoop : CHECKPOINTS_LOOPS) {
                    // Find PlayerStats closest to but not exceeding checkpoint
                    long bestLoop = -1;
                    for (long loop : playerStatsAtLoop.keySet()) {
                        if (loop <= cpLoop && loop > bestLoop) bestLoop = loop;
                    }
                    if (bestLoop < 0) continue;
                    final long checkLoop = bestLoop;

                    int[] stats = playerStatsAtLoop.get(bestLoop);
                    int gtMineralRate = stats[0];
                    int totalWorkers = stats[1];

                    // Count active MULEs at checkpoint
                    long activeMules = mules.stream().filter(m -> m.activeAt(checkLoop)).count();

                    if (activeMules == 0 || totalWorkers == 0) {
                        double[] a = cpAccum.get(cpLoop);
                        a[3]++;
                        continue;
                    }

                    // Estimate mineral-only workers: total - gas workers - MULEs
                    // SC2 workersActiveCount includes MULEs
                    int mineralWorkers = Math.max(0, totalWorkers - (int) activeMules);
                    // Subtract estimated gas workers (3 per gas building, but we don't have gas bldg count
                    // from PlayerStats — estimate 6 gas workers for Terran at 3-7 min)
                    int estimatedGasWorkers = 6;
                    mineralWorkers = Math.max(0, mineralWorkers - estimatedGasWorkers);

                    // Expected worker-only income per minute using calibrated rates
                    double workerIncomePerMin = approxMineralIncomePerTick(mineralWorkers) * TICKS_PER_MINUTE;

                    // MULE contribution = GT rate - worker income
                    double muleContribution = gtMineralRate - workerIncomePerMin;
                    if (muleContribution > 0) {
                        double perMuleRate = muleContribution / activeMules;
                        sumMuleIncome += perMuleRate;
                        sumMuleCount++;
                        checkpointsWithMules++;
                    }

                    double[] a = cpAccum.get(cpLoop);
                    a[0] += gtMineralRate;
                    a[1] += workerIncomePerMin;
                    a[2] += activeMules;
                    a[3]++;
                }
            }
        }

        System.out.println("=== MULE Income Calibration Report ===");
        System.out.printf("Terran player runs: %d%n", terranPlayers);
        System.out.printf("Checkpoints with active MULEs: %d%n%n", checkpointsWithMules);

        System.out.printf("Current SC2Data MULE income: %.1f minerals/min (%.3f per tick)%n",
            172.5, SC2Data.muleIncomePerTick());

        System.out.printf("%n%-12s  %10s  %12s  %8s  %10s  %6s%n",
            "Checkpoint", "GT MinRate", "WorkerIncome", "MULEs", "MULEIncome", "Runs");
        System.out.println("-".repeat(70));

        for (int cpLoop : CHECKPOINTS_LOOPS) {
            double[] a = cpAccum.get(cpLoop);
            int runs = (int) a[3];
            if (runs == 0) continue;
            int cpMin = (int) (cpLoop / (60 * SC2Data.GAME_LOOPS_PER_SECOND));
            double avgMules = a[2] / runs;
            if (avgMules > 0) {
                double avgGtRate = a[0] / runs;
                double avgWorkerIncome = a[1] / runs;
                double muleIncome = (a[0] - a[1]) / a[2];
                System.out.printf("%-12s  %10.1f  %12.1f  %8.2f  %10.1f  %6d%n",
                    cpMin + " min", avgGtRate, avgWorkerIncome, avgMules, muleIncome, runs);
            } else {
                System.out.printf("%-12s  %10s  %12s  %8s  %10s  %6d%n",
                    cpMin + " min", "-", "-", "0", "-", runs);
            }
        }

        if (sumMuleCount > 0) {
            double calibratedPerMinute = sumMuleIncome / sumMuleCount;
            double calibratedPerTick = calibratedPerMinute / 60.0 * SC2Data.LOOPS_PER_TICK / SC2Data.GAME_LOOPS_PER_SECOND;

            System.out.printf("%n=== Calibrated MULE Income ===%n");
            System.out.printf("  Per minute: %.1f minerals/min (was %.1f)%n", calibratedPerMinute, 172.5);
            System.out.printf("  Per tick:   %.3f (was %.3f)%n", calibratedPerTick, SC2Data.muleIncomePerTick());
            System.out.printf("  Scale:      %.2f×%n", calibratedPerMinute / 172.5);
            System.out.printf("  Observations: %d checkpoints with active MULEs%n", sumMuleCount);

            assertThat(calibratedPerMinute).as("MULE income should be positive and reasonable")
                .isBetween(50.0, 500.0);
        } else {
            System.out.println("\nNo checkpoints with active MULEs found — cannot calibrate");
        }
    }
}
