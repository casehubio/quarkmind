package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
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
import java.util.Set;

/**
 * Refs #562, #519
 */
@Tag("diagnostic")
class GasIncomeDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    private static final Set<BuildingType> TOWN_HALLS = Set.of(
            BuildingType.NEXUS, BuildingType.COMMAND_CENTER,
            BuildingType.ORBITAL_COMMAND, BuildingType.PLANETARY_FORTRESS,
            BuildingType.HATCHERY, BuildingType.LAIR, BuildingType.HIVE);

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
            double[] cumulativeGasIncome,
            int[] gtTownHallCounts, int[] emTownHallCounts,
            int firstGtGasTick, int firstEmGasTick) {}

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
                    int[] gtTownHallCounts = new int[CHECKPOINTS_MIN.length];
                    int[] emTownHallCounts = new int[CHECKPOINTS_MIN.length];
                    int firstGtGasTick = -1;
                    int firstEmGasTick = -1;

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

                        if (firstGtGasTick < 0) {
                            GameState gtSnap = groundTruth.snapshot();
                            if (gtSnap.playerEconomy().vespeneCollectionRate() > 0) {
                                firstGtGasTick = tick;
                            }
                        }
                        if (firstEmGasTick < 0 && emulated.totalGasWorkers() > 0) {
                            firstEmGasTick = tick;
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
                                gtTownHallCounts[i] = countTownHalls(gt);
                                emTownHallCounts[i] = countTownHalls(em);
                            }
                        }
                    }

                    results.add(new PerReplayGas(
                            replayPath.getFileName().toString(), playerId, race,
                            gtVesRate, emVesRate, gtVesCurrent, emVesCurrent,
                            gasSlotCounts, gasWorkerCounts, cumulativeGasIncome,
                            gtTownHallCounts, emTownHallCounts,
                            firstGtGasTick, firstEmGasTick));

                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("=== Gas Income Diagnostic (#562) ===");
        System.out.printf("Total player runs: %d%n%n", results.size());

        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            printCheckpoint(results, i, CHECKPOINTS_MIN[i]);
        }

        printDirectionBreakdown(results);
        printGasTimingAnalysis(results);
        printExpansionCorrelation(results);
        printPerWorkerEfficiency(results);
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

    private static int countTownHalls(GameState state) {
        return (int) state.myBuildings().stream()
                .filter(b -> TOWN_HALLS.contains(b.type()) && b.isComplete())
                .count();
    }

    private void printDirectionBreakdown(List<PerReplayGas> results) {
        System.out.println("=== DIRECTION BREAKDOWN — Over vs Under-collection ===");
        System.out.printf("%-5s %8s %8s %10s %10s %10s %10s%n",
                "CP", "Over", "Under", "OverMean", "UnderMean", "OverMAPE", "UnderMAPE");

        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            final int cpIdx = i;
            List<PerReplayGas> over = new ArrayList<>();
            List<PerReplayGas> under = new ArrayList<>();

            for (var r : results) {
                int diff = r.emVesRate[cpIdx] - r.gtVesRate[cpIdx];
                if (diff > 0) over.add(r);
                else if (diff < 0) under.add(r);
            }

            double overMean = over.stream()
                    .mapToInt(r -> r.emVesRate[cpIdx] - r.gtVesRate[cpIdx]).average().orElse(0);
            double underMean = under.stream()
                    .mapToInt(r -> r.gtVesRate[cpIdx] - r.emVesRate[cpIdx]).average().orElse(0);

            int overGtSum = over.stream().mapToInt(r -> r.gtVesRate[cpIdx]).sum();
            int overAbsErr = over.stream()
                    .mapToInt(r -> Math.abs(r.emVesRate[cpIdx] - r.gtVesRate[cpIdx])).sum();
            double overMape = overGtSum > 0 ? overAbsErr * 100.0 / overGtSum : 0;

            int underGtSum = under.stream().mapToInt(r -> r.gtVesRate[cpIdx]).sum();
            int underAbsErr = under.stream()
                    .mapToInt(r -> Math.abs(r.emVesRate[cpIdx] - r.gtVesRate[cpIdx])).sum();
            double underMape = underGtSum > 0 ? underAbsErr * 100.0 / underGtSum : 0;

            System.out.printf("%-5d %8d %8d %+10.1f %+10.1f %9.1f%% %9.1f%%%n",
                    CHECKPOINTS_MIN[cpIdx], over.size(), under.size(),
                    overMean, -underMean, overMape, underMape);
        }

        System.out.println();

        System.out.println("--- Per-race direction at 5-minute checkpoint ---");
        System.out.printf("%-10s %8s %8s %8s %12s %12s%n",
                "Race", "Over", "Under", "Exact", "OverMeanErr", "UnderMeanErr");

        int cpIdx = 2;
        String[] raceNames = {"PROTOSS", "TERRAN", "ZERG"};
        Race[] races = {Race.PROTOSS, Race.TERRAN, Race.ZERG};
        for (int ri = 0; ri < 3; ri++) {
            Race race = races[ri];
            List<PerReplayGas> raceResults = results.stream().filter(r -> r.race == race).toList();
            long rOver = raceResults.stream().filter(r -> r.emVesRate[cpIdx] > r.gtVesRate[cpIdx]).count();
            long rUnder = raceResults.stream().filter(r -> r.emVesRate[cpIdx] < r.gtVesRate[cpIdx]).count();
            long rExact = raceResults.stream().filter(r -> r.emVesRate[cpIdx] == r.gtVesRate[cpIdx]).count();

            double overMean = raceResults.stream()
                    .filter(r -> r.emVesRate[cpIdx] > r.gtVesRate[cpIdx])
                    .mapToInt(r -> r.emVesRate[cpIdx] - r.gtVesRate[cpIdx]).average().orElse(0);
            double underMean = raceResults.stream()
                    .filter(r -> r.emVesRate[cpIdx] < r.gtVesRate[cpIdx])
                    .mapToInt(r -> r.gtVesRate[cpIdx] - r.emVesRate[cpIdx]).average().orElse(0);

            System.out.printf("%-10s %8d %8d %8d %+12.1f %+12.1f%n",
                    raceNames[ri], rOver, rUnder, rExact, overMean, -underMean);
        }
        System.out.println();
    }

    private void printGasTimingAnalysis(List<PerReplayGas> results) {
        System.out.println("=== GAS TIMING ANALYSIS — First gas income tick ===");

        List<PerReplayGas> withBothGas = results.stream()
                .filter(r -> r.firstGtGasTick >= 0 && r.firstEmGasTick >= 0)
                .toList();

        if (withBothGas.isEmpty()) {
            System.out.println("No replays with both GT and EM gas income detected.\n");
            return;
        }

        double ticksPerSecond = SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK;

        long emEarlier = withBothGas.stream().filter(r -> r.firstEmGasTick < r.firstGtGasTick).count();
        long gtEarlier = withBothGas.stream().filter(r -> r.firstGtGasTick < r.firstEmGasTick).count();
        long same = withBothGas.stream().filter(r -> r.firstGtGasTick == r.firstEmGasTick).count();

        double avgGtTick = withBothGas.stream().mapToInt(r -> r.firstGtGasTick).average().orElse(0);
        double avgEmTick = withBothGas.stream().mapToInt(r -> r.firstEmGasTick).average().orElse(0);
        double avgDelta = withBothGas.stream()
                .mapToInt(r -> r.firstEmGasTick - r.firstGtGasTick).average().orElse(0);

        System.out.printf("Replays with both GT and EM gas: %d%n", withBothGas.size());
        System.out.printf("EM earlier: %d  GT earlier: %d  Same tick: %d%n", emEarlier, gtEarlier, same);
        System.out.printf("Avg GT first-gas tick: %.0f (%.0fs)  Avg EM first-gas tick: %.0f (%.0fs)%n",
                avgGtTick, avgGtTick / ticksPerSecond, avgEmTick, avgEmTick / ticksPerSecond);
        System.out.printf("Avg delta (EM - GT): %+.1f ticks (%+.1fs)%n%n", avgDelta, avgDelta / ticksPerSecond);

        System.out.println("--- Per-race gas timing ---");
        System.out.printf("%-10s %8s %10s %10s %12s %8s %8s%n",
                "Race", "Count", "AvgGT(s)", "AvgEM(s)", "AvgDelta(s)", "EMearly", "GTearly");

        String[] raceNames = {"PROTOSS", "TERRAN", "ZERG"};
        Race[] races = {Race.PROTOSS, Race.TERRAN, Race.ZERG};
        for (int ri = 0; ri < 3; ri++) {
            Race race = races[ri];
            List<PerReplayGas> raceResults = withBothGas.stream()
                    .filter(r -> r.race == race).toList();
            if (raceResults.isEmpty()) continue;

            double rAvgGt = raceResults.stream().mapToInt(r -> r.firstGtGasTick).average().orElse(0);
            double rAvgEm = raceResults.stream().mapToInt(r -> r.firstEmGasTick).average().orElse(0);
            double rAvgDelta = raceResults.stream()
                    .mapToInt(r -> r.firstEmGasTick - r.firstGtGasTick).average().orElse(0);
            long rEmEarlier = raceResults.stream().filter(r -> r.firstEmGasTick < r.firstGtGasTick).count();
            long rGtEarlier = raceResults.stream().filter(r -> r.firstGtGasTick < r.firstEmGasTick).count();

            System.out.printf("%-10s %8d %10.0f %10.0f %+12.1f %8d %8d%n",
                    raceNames[ri], raceResults.size(),
                    rAvgGt / ticksPerSecond, rAvgEm / ticksPerSecond,
                    rAvgDelta / ticksPerSecond, rEmEarlier, rGtEarlier);
        }
        System.out.println();
    }

    private void printExpansionCorrelation(List<PerReplayGas> results) {
        System.out.println("=== EXPANSION CORRELATION — Error by town hall count at 5-min ===");
        int cpIdx = 2;

        System.out.printf("%-6s %8s %10s %10s %12s %12s %8s%n",
                "GT TH", "Count", "AvgGTRate", "AvgEMRate", "AvgError", "AvgGasW", "AvgSlots");

        for (int th = 1; th <= 4; th++) {
            final int thCount = th;
            List<PerReplayGas> group = results.stream()
                    .filter(r -> r.gtTownHallCounts[cpIdx] == thCount).toList();
            if (group.isEmpty()) continue;

            double avgGtRate = group.stream().mapToInt(r -> r.gtVesRate[cpIdx]).average().orElse(0);
            double avgEmRate = group.stream().mapToInt(r -> r.emVesRate[cpIdx]).average().orElse(0);
            double avgError = group.stream()
                    .mapToInt(r -> r.emVesRate[cpIdx] - r.gtVesRate[cpIdx]).average().orElse(0);
            double avgGasW = group.stream().mapToInt(r -> r.gasWorkerCounts[cpIdx]).average().orElse(0);
            double avgSlots = group.stream().mapToInt(r -> r.gasSlotCounts[cpIdx]).average().orElse(0);

            System.out.printf("%-6d %8d %10.1f %10.1f %+12.1f %12.1f %8.1f%n",
                    th, group.size(), avgGtRate, avgEmRate, avgError, avgGasW, avgSlots);
        }

        System.out.println();

        System.out.println("--- Expansion count distribution at checkpoints (GT) ---");
        System.out.printf("%-5s", "CP");
        for (int th = 1; th <= 4; th++) System.out.printf(" %6s", "TH=" + th);
        System.out.println();

        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            final int cpI = i;
            System.out.printf("%-5d", CHECKPOINTS_MIN[i]);
            for (int th = 1; th <= 4; th++) {
                final int thCount = th;
                long count = results.stream()
                        .filter(r -> r.gtTownHallCounts[cpI] == thCount).count();
                System.out.printf(" %6d", count);
            }
            System.out.println();
        }
        System.out.println();

        System.out.println("--- GT vs EM town hall accuracy at 5-min ---");
        long thMatch = results.stream()
                .filter(r -> r.gtTownHallCounts[cpIdx] == r.emTownHallCounts[cpIdx]).count();
        long emMore = results.stream()
                .filter(r -> r.emTownHallCounts[cpIdx] > r.gtTownHallCounts[cpIdx]).count();
        long emFewer = results.stream()
                .filter(r -> r.emTownHallCounts[cpIdx] < r.gtTownHallCounts[cpIdx]).count();
        System.out.printf("Match: %d  EM more: %d  EM fewer: %d%n%n", thMatch, emMore, emFewer);
    }

    private void printPerWorkerEfficiency(List<PerReplayGas> results) {
        System.out.println("=== PER-WORKER GAS EFFICIENCY — GT rate per gas worker at each checkpoint ===");
        System.out.printf("%-5s %8s %12s %12s %12s%n",
                "CP", "Count", "AvgGTPerW", "AvgEMPerW", "Ratio(EM/GT)");

        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            final int cpIdx = i;
            List<PerReplayGas> withWorkers = results.stream()
                    .filter(r -> r.gasWorkerCounts[cpIdx] > 0 && r.gtVesRate[cpIdx] > 0)
                    .toList();
            if (withWorkers.isEmpty()) continue;

            double avgGtPerWorker = withWorkers.stream()
                    .mapToDouble(r -> (double) r.gtVesRate[cpIdx] / r.gasWorkerCounts[cpIdx])
                    .average().orElse(0);
            double avgEmPerWorker = withWorkers.stream()
                    .mapToDouble(r -> (double) r.emVesRate[cpIdx] / r.gasWorkerCounts[cpIdx])
                    .average().orElse(0);
            double ratio = avgGtPerWorker > 0 ? avgEmPerWorker / avgGtPerWorker : 0;

            System.out.printf("%-5d %8d %12.1f %12.1f %12.2f%n",
                    CHECKPOINTS_MIN[cpIdx], withWorkers.size(), avgGtPerWorker, avgEmPerWorker, ratio);
        }

        System.out.println();

        System.out.println("--- Per-race worker efficiency at 5-min ---");
        int cpIdx = 2;
        System.out.printf("%-10s %8s %12s %12s %12s%n",
                "Race", "Count", "GTPerWorker", "EMPerWorker", "Ratio");

        String[] raceNames = {"PROTOSS", "TERRAN", "ZERG"};
        Race[] races = {Race.PROTOSS, Race.TERRAN, Race.ZERG};
        for (int ri = 0; ri < 3; ri++) {
            Race race = races[ri];
            List<PerReplayGas> raceW = results.stream()
                    .filter(r -> r.race == race && r.gasWorkerCounts[cpIdx] > 0 && r.gtVesRate[cpIdx] > 0)
                    .toList();
            if (raceW.isEmpty()) continue;

            double gtPW = raceW.stream()
                    .mapToDouble(r -> (double) r.gtVesRate[cpIdx] / r.gasWorkerCounts[cpIdx])
                    .average().orElse(0);
            double emPW = raceW.stream()
                    .mapToDouble(r -> (double) r.emVesRate[cpIdx] / r.gasWorkerCounts[cpIdx])
                    .average().orElse(0);
            double ratio = gtPW > 0 ? emPW / gtPW : 0;

            System.out.printf("%-10s %8d %12.1f %12.1f %12.2f%n",
                    raceNames[ri], raceW.size(), gtPW, emPW, ratio);
        }
        System.out.println();
    }

    private void printWorstReplays(List<PerReplayGas> results, int cpIdx, int limit) {
        int cpMin = CHECKPOINTS_MIN[cpIdx];
        System.out.printf("=== WORST %d REPLAYS — Vespene Rate Error (%d min) ===%n", limit, cpMin);
        System.out.printf("%-45s %4s %6s %6s %6s %6s %5s %5s %8s %5s %5s%n",
                "Replay", "P", "Race", "GTrt", "EMrt", "Error", "Slot", "GasW", "CumGas", "gtTH", "emTH");

        results.stream()
                .sorted((a, b) -> Integer.compare(
                        Math.abs(b.gtVesRate[cpIdx] - b.emVesRate[cpIdx]),
                        Math.abs(a.gtVesRate[cpIdx] - a.emVesRate[cpIdx])))
                .limit(limit)
                .forEach(r -> System.out.printf("%-45s %4d %6s %,6d %,6d %+,6d %5d %5d %,8.0f %5d %5d%n",
                        r.replayName.substring(0, Math.min(45, r.replayName.length())),
                        r.playerId, r.race,
                        r.gtVesRate[cpIdx], r.emVesRate[cpIdx],
                        r.emVesRate[cpIdx] - r.gtVesRate[cpIdx],
                        r.gasSlotCounts[cpIdx], r.gasWorkerCounts[cpIdx],
                        r.cumulativeGasIncome[cpIdx],
                        r.gtTownHallCounts[cpIdx], r.emTownHallCounts[cpIdx]));
        System.out.println();
    }
}
