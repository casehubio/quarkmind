package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.trackerevents.PlayerStatsEvent;
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
import java.util.List;

/**
 * Refs #593
 */
@Tag("diagnostic")
class RateAlgorithmAnalysisTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final double LOOPS_PER_MINUTE = 60.0 * SC2Data.GAME_LOOPS_PER_SECOND;
    private static final int MAX_LOOP = (int) (7 * LOOPS_PER_MINUTE);

    record StatsSnapshot(int loop, int mineralsCurrent, int mineralsCollRate,
                         int usedCurrentArmy, int usedCurrentEcon, int usedCurrentTech,
                         int usedInProgressArmy, int usedInProgressEcon, int usedInProgressTech,
                         int lostArmy, int lostEcon, int lostTech,
                         int vespeneCurrent, int vespeneCollRate,
                         int gasUsedCurrentArmy, int gasUsedCurrentEcon, int gasUsedCurrentTech,
                         int gasUsedInProgressArmy, int gasUsedInProgressEcon, int gasUsedInProgressTech,
                         int gasLostArmy, int gasLostEcon, int gasLostTech,
                         int workersActive) {

        int totalMineralsMined() {
            return mineralsCurrent + usedCurrentArmy + usedCurrentEcon + usedCurrentTech
                    + usedInProgressArmy + usedInProgressEcon + usedInProgressTech
                    + lostArmy + lostEcon + lostTech;
        }

        int totalVespeneMined() {
            return vespeneCurrent + gasUsedCurrentArmy + gasUsedCurrentEcon + gasUsedCurrentTech
                    + gasUsedInProgressArmy + gasUsedInProgressEcon + gasUsedInProgressTech
                    + gasLostArmy + gasLostEcon + gasLostTech;
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
    void analyseRateAlgorithm() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().limit(30).toList();
        }

        double[] alphas = {0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0};

        double[][] mineralErrors = new double[alphas.length + 1][2];
        double[][] vespeneErrors = new double[alphas.length + 1][2];
        int totalIntervals = 0;

        System.out.println("=== SC2 Rate Algorithm Reverse-Engineering ===\n");
        System.out.println("Method: compute income from delta(totalMined) between consecutive");
        System.out.println("PlayerStatsEvents, then compare simple window and EMA against");
        System.out.println("SC2's reported mineralsCollectionRate.\n");

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                        EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.trackerEvents == null) continue;
            if (replay.details == null || replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                List<StatsSnapshot> snapshots = extractSnapshots(replay, playerId);
                if (snapshots.size() < 5) continue;

                double[] emaMinRates = new double[alphas.length];
                double[] emaGasRates = new double[alphas.length];

                for (int i = 1; i < snapshots.size(); i++) {
                    StatsSnapshot prev = snapshots.get(i - 1);
                    StatsSnapshot cur = snapshots.get(i);
                    int elapsedLoops = cur.loop - prev.loop;
                    if (elapsedLoops <= 0) continue;

                    int mineralIncome = cur.totalMineralsMined() - prev.totalMineralsMined();
                    int vespeneIncome = cur.totalVespeneMined() - prev.totalVespeneMined();

                    double instantMineralRate = mineralIncome / (double) elapsedLoops * LOOPS_PER_MINUTE;
                    double instantVespeneRate = vespeneIncome / (double) elapsedLoops * LOOPS_PER_MINUTE;

                    double simpleMinRate = instantMineralRate;
                    double simpleGasRate = instantVespeneRate;

                    double sc2MinRate = cur.mineralsCollRate;
                    double sc2GasRate = cur.vespeneCollRate;

                    mineralErrors[0][0] += Math.abs(simpleMinRate - sc2MinRate);
                    mineralErrors[0][1] += Math.abs(sc2MinRate);
                    vespeneErrors[0][0] += Math.abs(simpleGasRate - sc2GasRate);
                    vespeneErrors[0][1] += Math.abs(sc2GasRate);

                    for (int ai = 0; ai < alphas.length; ai++) {
                        if (i == 1) {
                            emaMinRates[ai] = instantMineralRate;
                            emaGasRates[ai] = instantVespeneRate;
                        } else {
                            emaMinRates[ai] = alphas[ai] * instantMineralRate + (1 - alphas[ai]) * emaMinRates[ai];
                            emaGasRates[ai] = alphas[ai] * instantVespeneRate + (1 - alphas[ai]) * emaGasRates[ai];
                        }
                        mineralErrors[ai + 1][0] += Math.abs(emaMinRates[ai] - sc2MinRate);
                        mineralErrors[ai + 1][1] += Math.abs(sc2MinRate);
                        vespeneErrors[ai + 1][0] += Math.abs(emaGasRates[ai] - sc2GasRate);
                        vespeneErrors[ai + 1][1] += Math.abs(sc2GasRate);
                    }

                    totalIntervals++;
                }
            }
        }

        System.out.printf("Analysed %d rate intervals across %d replays%n%n", totalIntervals, replays.size());

        System.out.printf("%-20s | %-12s | %-12s%n", "Algorithm", "Min MAPE", "Gas MAPE");
        System.out.println("-".repeat(50));

        String[] labels = new String[alphas.length + 1];
        labels[0] = "Simple window";
        for (int i = 0; i < alphas.length; i++) {
            labels[i + 1] = String.format("EMA α=%.2f", alphas[i]);
        }

        double bestMinMape = Double.MAX_VALUE;
        int bestMinIdx = 0;
        double bestGasMape = Double.MAX_VALUE;
        int bestGasIdx = 0;

        for (int i = 0; i < labels.length; i++) {
            double minMape = mineralErrors[i][1] != 0 ? mineralErrors[i][0] / mineralErrors[i][1] * 100 : 0;
            double gasMape = vespeneErrors[i][1] != 0 ? vespeneErrors[i][0] / vespeneErrors[i][1] * 100 : 0;
            System.out.printf("%-20s | %-11.1f%% | %-11.1f%%%n", labels[i], minMape, gasMape);
            if (minMape < bestMinMape) { bestMinMape = minMape; bestMinIdx = i; }
            if (gasMape < bestGasMape) { bestGasMape = gasMape; bestGasIdx = i; }
        }

        System.out.printf("%nBest mineral fit: %s (%.1f%%)%n", labels[bestMinIdx], bestMinMape);
        System.out.printf("Best vespene fit: %s (%.1f%%)%n", labels[bestGasIdx], bestGasMape);

        System.out.printf("%n=== Conclusion ===%n");
        if (bestMinIdx == 0 && bestGasIdx == 0) {
            System.out.println("SC2 uses a simple windowed average — matches our EconomyTracker algorithm.");
        } else if (bestMinIdx == 0) {
            System.out.printf("Minerals: simple window. Vespene: %s.%n", labels[bestGasIdx]);
        } else {
            System.out.printf("SC2 likely uses %s for minerals, %s for vespene.%n",
                    labels[bestMinIdx], labels[bestGasIdx]);
            System.out.println("Consider switching EconomyTracker to match.");
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void perIntervalRateTrace() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().limit(3).toList();
        }

        System.out.println("=== Per-Interval Rate Trace (first 3 replays, player 1) ===\n");

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                        EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.trackerEvents == null) continue;

            List<StatsSnapshot> snapshots = extractSnapshots(replay, 1);
            if (snapshots.size() < 3) continue;

            System.out.printf("--- %s ---%n", replayPath.getFileName());
            System.out.printf("%-8s %-8s %-12s %-12s %-12s %-8s%n",
                    "Loop", "Min", "SC2Rate", "SimpleRate", "Delta", "Workers");
            System.out.println("-".repeat(70));

            for (int i = 1; i < snapshots.size() && snapshots.get(i).loop < MAX_LOOP; i++) {
                StatsSnapshot prev = snapshots.get(i - 1);
                StatsSnapshot cur = snapshots.get(i);
                int elapsed = cur.loop - prev.loop;
                if (elapsed <= 0) continue;

                int income = cur.totalMineralsMined() - prev.totalMineralsMined();
                double simpleRate = income / (double) elapsed * LOOPS_PER_MINUTE;
                double delta = simpleRate - cur.mineralsCollRate;

                double minutes = cur.loop / LOOPS_PER_MINUTE;
                System.out.printf("%-8d %-8.2f %-12d %-12.0f %-+12.0f %-8d%n",
                        cur.loop, minutes, cur.mineralsCollRate, simpleRate, delta, cur.workersActive);
            }
            System.out.println();
        }
    }

    private List<StatsSnapshot> extractSnapshots(Replay replay, int playerId) {
        List<StatsSnapshot> result = new ArrayList<>();
        for (var event : replay.trackerEvents.events) {
            if (event.getId() != ITrackerEvents.ID_PLAYER_STATS) continue;
            if (event.getPlayerId() == null || event.getPlayerId() != playerId) continue;
            if (event.getLoop() > MAX_LOOP) break;

            IPlayerStatsEvent ps = (IPlayerStatsEvent) event;
            result.add(new StatsSnapshot(
                    event.getLoop(),
                    safe(ps.getMineralsCurrent()), safe(ps.getMinsCollRate()),
                    safe(ps.getMinsUsedInCurrentArmy()), safe(ps.getMinsUsedInCurrentEcon()), safe(ps.getMinsUsedInCurrentTech()),
                    safe(ps.getMinsUsedInProgressArmy()), safe(ps.getMinsUsedInProgressEcon()), safe(ps.getMinsUsedInProgressTech()),
                    safe(ps.getMinsLostArmy()), safe(ps.getMinsLostEcon()), safe(ps.getMinsLostTech()),
                    safe(ps.getGasCurrent()), safe(ps.getGasCollRate()),
                    safe(ps.getGasUsedInCurrentArmy()), safe(ps.getGasUsedInCurrentEcon()), safe(ps.getGasUsedInCurrentTech()),
                    safe(ps.getGasUsedInProgressArmy()), safe(ps.getGasUsedInProgressEcon()), safe(ps.getGasUsedInProgressTech()),
                    safe(ps.getGasLostArmy()), safe(ps.getGasLostEcon()), safe(ps.getGasLostTech()),
                    safe(ps.getWorkersActiveCount())));
        }
        return result;
    }

    private static int safe(Integer v) { return v != null ? v : 0; }
}
