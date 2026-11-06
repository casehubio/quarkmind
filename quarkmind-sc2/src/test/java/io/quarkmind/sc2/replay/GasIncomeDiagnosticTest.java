package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
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
 * Refs #519
 */
@Tag("diagnostic")
class GasIncomeDiagnosticTest {

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

    record PerReplayGas(
            String replayName, int playerId, Race race,
            int[] gtVesRate, int[] emVesRate,
            int[] gtVesCurrent, int[] emVesCurrent,
            int[] gasSlotCounts, int[] gasWorkerCounts,
            double[] cumulativeGasIncome) {}

    @Test
    @EnabledIf("oracleExists")
    void gasIncomeVarianceBreakdown() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().toList();
        }

        List<PerReplayGas> results = new ArrayList<>();

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                        EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null || replay.trackerEvents == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
                    groundTruth.reset();
                    emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                    emulated.resetForHarness();

                    int cursor = 0;
                    int[] gtVesRate = new int[CHECKPOINTS_MIN.length];
                    int[] emVesRate = new int[CHECKPOINTS_MIN.length];
                    int[] gtVesCurrent = new int[CHECKPOINTS_MIN.length];
                    int[] emVesCurrent = new int[CHECKPOINTS_MIN.length];
                    int[] gasSlotCounts = new int[CHECKPOINTS_MIN.length];
                    int[] gasWorkerCounts = new int[CHECKPOINTS_MIN.length];
                    double[] cumulativeGasIncome = new double[CHECKPOINTS_MIN.length];
                    for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
                        long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;
                        emulated.tick();
                        groundTruth.tick();

                        emulated.processDeferredTrainIntents();
                        emulated.processDeferredResearchIntents();

                        while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                            emulated.applyIntent(intents.get(cursor));
                            cursor++;
                        }

                        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
                            if (tick == CHECKPOINTS_MIN[i] * TICKS_PER_MINUTE - 1) {
                                GameState gt = groundTruth.snapshot();
                                GameState em = emulated.snapshot();
                                gtVesRate[i] = gt.playerEconomy().vespeneCollectionRate();
                                emVesRate[i] = em.playerEconomy().vespeneCollectionRate();
                                gtVesCurrent[i] = gt.playerEconomy().vespeneCurrent();
                                emVesCurrent[i] = em.playerEconomy().vespeneCurrent();
                                gasSlotCounts[i] = emulated.gasSlotCount();
                                gasWorkerCounts[i] = emulated.totalGasWorkers();
                                cumulativeGasIncome[i] = emulated.cumulativeGasIncome();
                            }
                        }
                    }

                    results.add(new PerReplayGas(
                            replayPath.getFileName().toString(), playerId, race,
                            gtVesRate, emVesRate, gtVesCurrent, emVesCurrent,
                            gasSlotCounts, gasWorkerCounts, cumulativeGasIncome));

                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("=== Gas Income Diagnostic (#519) ===");
        System.out.printf("Total player runs: %d%n%n", results.size());

        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            printCheckpoint(results, i, CHECKPOINTS_MIN[i]);
        }

        printWorstReplays(results, 2, 20);
    }

    private void printCheckpoint(List<PerReplayGas> results, int cpIdx, int cpMin) {
        System.out.printf("=== %d-minute checkpoint ===%n", cpMin);

        int totalGtRate = 0, totalEmRate = 0, totalRateAbsErr = 0;
        int totalGtCurrent = 0, totalEmCurrent = 0, totalCurrentAbsErr = 0;
        int totalSlots = 0, totalGasWorkers = 0;
        double totalCumulativeGas = 0;
        int countWithGas = 0;

        for (var r : results) {
            totalGtRate += r.gtVesRate[cpIdx];
            totalEmRate += r.emVesRate[cpIdx];
            totalRateAbsErr += Math.abs(r.gtVesRate[cpIdx] - r.emVesRate[cpIdx]);
            totalGtCurrent += r.gtVesCurrent[cpIdx];
            totalEmCurrent += r.emVesCurrent[cpIdx];
            totalCurrentAbsErr += Math.abs(r.gtVesCurrent[cpIdx] - r.emVesCurrent[cpIdx]);
            totalSlots += r.gasSlotCounts[cpIdx];
            totalGasWorkers += r.gasWorkerCounts[cpIdx];
            totalCumulativeGas += r.cumulativeGasIncome[cpIdx];
            if (r.gasSlotCounts[cpIdx] > 0) countWithGas++;
        }

        double rateMape = totalGtRate > 0 ? totalRateAbsErr * 100.0 / totalGtRate : 0;
        double currentMape = totalGtCurrent > 0 ? totalCurrentAbsErr * 100.0 / totalGtCurrent : 0;

        System.out.printf("%-30s %12s %12s %8s %8s%n", "Field", "GT sum", "EM sum", "AbsErr", "MAPE");
        System.out.printf("%-30s %,12d %,12d %,8d %7.1f%%%n", "vespeneCollectionRate",
                totalGtRate, totalEmRate, totalRateAbsErr, rateMape);
        System.out.printf("%-30s %,12d %,12d %,8d %7.1f%%%n", "vespeneCurrent",
                totalGtCurrent, totalEmCurrent, totalCurrentAbsErr, currentMape);
        System.out.printf("Replays with gas: %d/%d  Avg slots: %.1f  Avg gas workers: %.1f  Avg cumulative gas: %.0f%n%n",
                countWithGas, results.size(),
                results.isEmpty() ? 0 : totalSlots * 1.0 / results.size(),
                results.isEmpty() ? 0 : totalGasWorkers * 1.0 / results.size(),
                results.isEmpty() ? 0 : totalCumulativeGas / results.size());

        int[] rateByRace = new int[3];
        int[] emRateByRace = new int[3];
        int[] absErrByRace = new int[3];
        int[] countByRace = new int[3];
        for (var r : results) {
            int ri = r.race == Race.PROTOSS ? 0 : r.race == Race.TERRAN ? 1 : 2;
            rateByRace[ri] += r.gtVesRate[cpIdx];
            emRateByRace[ri] += r.emVesRate[cpIdx];
            absErrByRace[ri] += Math.abs(r.gtVesRate[cpIdx] - r.emVesRate[cpIdx]);
            countByRace[ri]++;
        }
        String[] raceNames = {"PROTOSS", "TERRAN", "ZERG"};
        System.out.printf("%-10s %8s %12s %12s %8s %8s%n", "Race", "Count", "GT Rate", "EM Rate", "AbsErr", "MAPE");
        for (int ri = 0; ri < 3; ri++) {
            double mape = rateByRace[ri] > 0 ? absErrByRace[ri] * 100.0 / rateByRace[ri] : 0;
            System.out.printf("%-10s %8d %,12d %,12d %,8d %7.1f%%%n",
                    raceNames[ri], countByRace[ri], rateByRace[ri], emRateByRace[ri], absErrByRace[ri], mape);
        }
        System.out.println();
    }

    private void printWorstReplays(List<PerReplayGas> results, int cpIdx, int limit) {
        int cpMin = CHECKPOINTS_MIN[cpIdx];
        System.out.printf("=== WORST %d REPLAYS — Vespene Rate Error (%d min) ===%n", limit, cpMin);
        System.out.printf("%-45s %4s %6s %6s %6s %6s %5s %5s %8s%n",
                "Replay", "P", "Race", "GTrt", "EMrt", "Error", "Slot", "GasW", "CumGas");

        results.stream()
                .sorted((a, b) -> Integer.compare(
                        Math.abs(b.gtVesRate[cpIdx] - b.emVesRate[cpIdx]),
                        Math.abs(a.gtVesRate[cpIdx] - a.emVesRate[cpIdx])))
                .limit(limit)
                .forEach(r -> System.out.printf("%-45s %4d %6s %,6d %,6d %+,6d %5d %5d %,8.0f%n",
                        r.replayName.substring(0, Math.min(45, r.replayName.length())),
                        r.playerId, r.race,
                        r.gtVesRate[cpIdx], r.emVesRate[cpIdx],
                        r.emVesRate[cpIdx] - r.gtVesRate[cpIdx],
                        r.gasSlotCounts[cpIdx], r.gasWorkerCounts[cpIdx],
                        r.cumulativeGasIncome[cpIdx]));
        System.out.println();
    }
}
