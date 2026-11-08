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
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Refs #532
 */
@Tag("diagnostic")
class VespeneSpendingDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int CHECKPOINT_MIN = 5;
    private static final int CHECKPOINT_TICK = CHECKPOINT_MIN * TICKS_PER_MINUTE - 1;
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 6;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    record PerReplayResult(
            String replayName, int playerId, Race race,
            int gtVesArmy, int emVesArmy, int armyError,
            int gtVesTech, int emVesTech, int techError,
            Map<UnitType, Integer> aliveArmyGasUnits,
            Map<BuildingType, Integer> aliveTechGasBuildings,
            List<String> completedUpgrades,
            EmulatedGame.TechSpendingBreakdown techBreakdown,
            int gtVesInProgressArmy, int gtVesInProgressTech,
            int gtVesLostArmy, int gtVesLostTech) {}

    @Test
    @EnabledIf("oracleExists")
    void vespeneSpendingVarianceBreakdown() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().toList();
        }

        List<PerReplayResult> results = new ArrayList<>();

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

                    int[] gtInProgressLost = extractGtInProgressAndLost(replay, playerId);

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
                    groundTruth.reset();
                    emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                    emulated.resetForHarness();

                    int cursor = 0;
                    GameState gtState = null;
                    GameState emState = null;
                    EmulatedGame.TechSpendingBreakdown techBd = null;

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

                        if (tick == CHECKPOINT_TICK) {
                            gtState = groundTruth.snapshot();
                            emState = emulated.snapshot();
                            techBd = emulated.techSpendingBreakdown();
                        }
                    }
                    if (gtState == null || emState == null) continue;

                    PlayerEconomyStats gtEcon = gtState.playerEconomy();
                    PlayerEconomyStats emEcon = emState.playerEconomy();

                    Map<UnitType, Integer> aliveArmyGasUnits = new EnumMap<>(UnitType.class);
                    for (Unit u : emState.myUnits()) {
                        if (SC2Data.gasCost(u.type()) > 0
                                && !SC2Data.isWorker(u.type())
                                && SC2Data.unitSupplyProvided(u.type()) <= 0) {
                            aliveArmyGasUnits.merge(u.type(), 1, Integer::sum);
                        }
                    }

                    Map<BuildingType, Integer> aliveTechGasBuildings = new EnumMap<>(BuildingType.class);
                    for (var b : emState.myBuildings()) {
                        if (b.isComplete() && SC2Data.gasCost(b.type()) > 0
                                && !EmulatedGame.isEconomyBuilding(b.type())) {
                            aliveTechGasBuildings.merge(b.type(), 1, Integer::sum);
                        }
                    }

                    List<String> completedUpgrades = new ArrayList<>(emState.playerUpgrades());

                    int gtVA = gtEcon.vespeneUsedCurrentArmy();
                    int emVA = emEcon.vespeneUsedCurrentArmy();
                    int gtVT = gtEcon.vespeneUsedCurrentTechnology();
                    int emVT = emEcon.vespeneUsedCurrentTechnology();

                    results.add(new PerReplayResult(
                            replayPath.getFileName().toString(), playerId, race,
                            gtVA, emVA, emVA - gtVA,
                            gtVT, emVT, emVT - gtVT,
                            aliveArmyGasUnits, aliveTechGasBuildings, completedUpgrades, techBd,
                            gtInProgressLost[0], gtInProgressLost[1],
                            gtInProgressLost[2], gtInProgressLost[3]));

                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("=== Vespene Spending Variance Diagnostic (#532) ===");
        System.out.printf("Total player runs: %d, Checkpoint: %d min%n%n", results.size(), CHECKPOINT_MIN);

        printArmySummary(results);
        printTechSummary(results);
        printWorstArmyReplays(results, 20);
        printWorstTechReplays(results, 20);
        printInProgressAnalysis(results);
    }

    private void printArmySummary(List<PerReplayResult> results) {
        System.out.println("=== VESPENE ARMY — Per Unit Type Contribution ===");
        Map<UnitType, int[]> unitTypeAccum = new EnumMap<>(UnitType.class);
        int totalGt = 0, totalEm = 0, totalAbsErr = 0;
        int overCount = 0, underCount = 0, exactCount = 0;

        for (var r : results) {
            totalGt += r.gtVesArmy;
            totalEm += r.emVesArmy;
            totalAbsErr += Math.abs(r.armyError);
            if (r.armyError > 0) overCount++;
            else if (r.armyError < 0) underCount++;
            else exactCount++;

            for (var e : r.aliveArmyGasUnits.entrySet()) {
                unitTypeAccum.computeIfAbsent(e.getKey(), k -> new int[]{0, 0});
                int[] acc = unitTypeAccum.get(e.getKey());
                acc[0] += e.getValue();
                acc[1] += e.getValue() * SC2Data.gasCost(e.getKey());
            }
        }

        System.out.printf("GT sum: %,d  EM sum: %,d  Bias: %+,d  AbsErr: %,d  MAPE: %.1f%%%n",
                totalGt, totalEm, totalEm - totalGt, totalAbsErr,
                totalGt > 0 ? totalAbsErr * 100.0 / totalGt : 0);
        System.out.printf("Over: %d  Under: %d  Exact: %d%n%n", overCount, underCount, exactCount);

        System.out.printf("%-20s %8s %12s %8s%n", "UnitType", "Count", "TotalGasCost", "UnitCost");
        unitTypeAccum.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(e -> System.out.printf("%-20s %,8d %,12d %8d%n",
                        e.getKey(), e.getValue()[0], e.getValue()[1], SC2Data.gasCost(e.getKey())));
        System.out.println();
    }

    private void printTechSummary(List<PerReplayResult> results) {
        System.out.println("=== VESPENE TECH — Component Breakdown ===");
        int totalGt = 0, totalEm = 0, totalAbsErr = 0;
        int overCount = 0, underCount = 0, exactCount = 0;
        Map<BuildingType, int[]> buildingAccum = new EnumMap<>(BuildingType.class);
        Map<String, int[]> upgradeAccum = new TreeMap<>();
        int totalBuildGas = 0, totalMorphGas = 0, totalResearchGas = 0, totalDestroyGas = 0;

        for (var r : results) {
            totalGt += r.gtVesTech;
            totalEm += r.emVesTech;
            totalAbsErr += Math.abs(r.techError);
            if (r.techError > 0) overCount++;
            else if (r.techError < 0) underCount++;
            else exactCount++;

            for (var e : r.aliveTechGasBuildings.entrySet()) {
                buildingAccum.computeIfAbsent(e.getKey(), k -> new int[]{0, 0});
                int[] acc = buildingAccum.get(e.getKey());
                acc[0] += e.getValue();
                acc[1] += e.getValue() * SC2Data.gasCost(e.getKey());
            }
            for (String upName : r.completedUpgrades) {
                UpgradeType ut = UpgradeType.fromPythonName(upName);
                if (ut != null && SC2Data.upgradeVespeneCost(ut) > 0) {
                    upgradeAccum.computeIfAbsent(upName, k -> new int[]{0, 0});
                    int[] acc = upgradeAccum.get(upName);
                    acc[0]++;
                    acc[1] += SC2Data.upgradeVespeneCost(ut);
                }
            }
            if (r.techBreakdown != null) {
                totalBuildGas += r.techBreakdown.buildSpending().values().stream().mapToInt(a -> a[1]).sum();
                totalMorphGas += r.techBreakdown.morphSpending().values().stream().mapToInt(a -> a[1]).sum();
                totalResearchGas += r.techBreakdown.researchVespene();
                totalDestroyGas += r.techBreakdown.destroySubtractions().values().stream().mapToInt(a -> a[1]).sum();
            }
        }

        System.out.printf("GT sum: %,d  EM sum: %,d  Bias: %+,d  AbsErr: %,d  MAPE: %.1f%%%n",
                totalGt, totalEm, totalEm - totalGt, totalAbsErr,
                totalGt > 0 ? totalAbsErr * 100.0 / totalGt : 0);
        System.out.printf("Over: %d  Under: %d  Exact: %d%n%n", overCount, underCount, exactCount);

        System.out.println("--- EM Spending Components ---");
        System.out.printf("%-30s %,12d%n", "Build (tech buildings gas)", totalBuildGas);
        System.out.printf("%-30s %,12d%n", "Morph (tech buildings gas)", totalMorphGas);
        System.out.printf("%-30s %,12d%n", "Research (upgrade gas)", totalResearchGas);
        System.out.printf("%-30s %,12d%n", "Destroy (subtracted gas)", -totalDestroyGas);
        System.out.printf("%-30s %,12d%n%n", "Net EM tech vespene",
                totalBuildGas + totalMorphGas + totalResearchGas - totalDestroyGas);

        System.out.println("--- Alive Tech Buildings with Gas Cost ---");
        System.out.printf("%-25s %8s %12s %8s%n", "BuildingType", "Count", "TotalGas", "UnitCost");
        buildingAccum.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(e -> System.out.printf("%-25s %,8d %,12d %8d%n",
                        e.getKey(), e.getValue()[0], e.getValue()[1], SC2Data.gasCost(e.getKey())));

        System.out.println();
        System.out.println("--- Completed Upgrades with Gas Cost ---");
        System.out.printf("%-35s %8s %12s %8s%n", "Upgrade", "Count", "TotalGas", "UnitCost");
        upgradeAccum.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(e -> System.out.printf("%-35s %,8d %,12d %8d%n",
                        e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] > 0 ? e.getValue()[1] / e.getValue()[0] : 0));
        System.out.println();
    }

    private void printWorstArmyReplays(List<PerReplayResult> results, int limit) {
        System.out.printf("=== WORST %d REPLAYS — Vespene Army Error ===%n", limit);
        System.out.printf("%-45s %4s %6s %6s %6s %6s   %s%n",
                "Replay", "P", "Race", "GT", "EM", "Error", "Top gas units (alive)");

        results.stream()
                .sorted((a, b) -> Integer.compare(Math.abs(b.armyError), Math.abs(a.armyError)))
                .limit(limit)
                .forEach(r -> {
                    StringBuilder units = new StringBuilder();
                    r.aliveArmyGasUnits.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(
                                    b.getValue() * SC2Data.gasCost(b.getKey()),
                                    a.getValue() * SC2Data.gasCost(a.getKey())))
                            .limit(5)
                            .forEach(e -> {
                                if (!units.isEmpty()) units.append(", ");
                                units.append(String.format("%dx%s(%dg)", e.getValue(), e.getKey(),
                                        SC2Data.gasCost(e.getKey())));
                            });
                    System.out.printf("%-45s %4d %6s %,6d %,6d %+,6d   %s%n",
                            r.replayName.substring(0, Math.min(45, r.replayName.length())),
                            r.playerId, r.race,
                            r.gtVesArmy, r.emVesArmy, r.armyError, units);
                });
        System.out.println();
    }

    private void printWorstTechReplays(List<PerReplayResult> results, int limit) {
        System.out.printf("=== WORST %d REPLAYS — Vespene Tech Error ===%n", limit);
        System.out.printf("%-45s %4s %6s %6s %6s %6s   %s%n",
                "Replay", "P", "Race", "GT", "EM", "Error", "Breakdown (bldg/research/destroy)");

        results.stream()
                .sorted((a, b) -> Integer.compare(Math.abs(b.techError), Math.abs(a.techError)))
                .limit(limit)
                .forEach(r -> {
                    StringBuilder detail = new StringBuilder();
                    if (r.techBreakdown != null) {
                        int bGas = r.techBreakdown.buildSpending().values().stream().mapToInt(a -> a[1]).sum();
                        int mGas = r.techBreakdown.morphSpending().values().stream().mapToInt(a -> a[1]).sum();
                        int rGas = r.techBreakdown.researchVespene();
                        int dGas = r.techBreakdown.destroySubtractions().values().stream().mapToInt(a -> a[1]).sum();
                        detail.append(String.format("bld=%d mrp=%d res=%d dst=-%d", bGas, mGas, rGas, dGas));
                    }
                    if (!r.aliveTechGasBuildings.isEmpty()) {
                        detail.append(" | ");
                        r.aliveTechGasBuildings.entrySet().stream()
                                .sorted((a, b) -> Integer.compare(
                                        b.getValue() * SC2Data.gasCost(b.getKey()),
                                        a.getValue() * SC2Data.gasCost(a.getKey())))
                                .limit(3)
                                .forEach(e -> detail.append(String.format("%dx%s(%dg) ",
                                        e.getValue(), e.getKey(), SC2Data.gasCost(e.getKey()))));
                    }
                    System.out.printf("%-45s %4d %6s %,6d %,6d %+,6d   %s%n",
                            r.replayName.substring(0, Math.min(45, r.replayName.length())),
                            r.playerId, r.race,
                            r.gtVesTech, r.emVesTech, r.techError, detail);
                });
        System.out.println();
    }

    private void printInProgressAnalysis(List<PerReplayResult> results) {
        System.out.println("=== GT InProgress and Lost — Vespene (from PlayerStatsEvent) ===");
        int totalInProgressArmy = 0, totalInProgressTech = 0;
        int totalLostArmy = 0, totalLostTech = 0;
        int inProgressArmyCount = 0, inProgressTechCount = 0;
        int lostArmyCount = 0, lostTechCount = 0;

        for (var r : results) {
            totalInProgressArmy += r.gtVesInProgressArmy;
            totalInProgressTech += r.gtVesInProgressTech;
            totalLostArmy += r.gtVesLostArmy;
            totalLostTech += r.gtVesLostTech;
            if (r.gtVesInProgressArmy > 0) inProgressArmyCount++;
            if (r.gtVesInProgressTech > 0) inProgressTechCount++;
            if (r.gtVesLostArmy > 0) lostArmyCount++;
            if (r.gtVesLostTech > 0) lostTechCount++;
        }

        System.out.printf("%-30s %12s %12s%n", "Field", "Sum", "Non-zero count");
        System.out.printf("%-30s %,12d %12d%n", "InProgress Army", totalInProgressArmy, inProgressArmyCount);
        System.out.printf("%-30s %,12d %12d%n", "InProgress Tech", totalInProgressTech, inProgressTechCount);
        System.out.printf("%-30s %,12d %12d%n", "Lost Army", totalLostArmy, lostArmyCount);
        System.out.printf("%-30s %,12d %12d%n", "Lost Tech", totalLostTech, lostTechCount);
        System.out.println();

        System.out.println("--- Correlation: error vs InProgress+Lost ---");
        System.out.println("Army: EM tracks 'Current' only. GT 'Current' excludes InProgress and Lost.");
        System.out.println("If EM overcounts: might be counting in-progress units as completed.");
        System.out.println("If EM undercounts: might be subtracting deaths that GT counts as 'Lost' not 'Current'.");
        System.out.println();

        int armyOverWithInProgress = 0, armyUnderWithLost = 0;
        int techOverWithInProgress = 0, techUnderWithLost = 0;

        for (var r : results) {
            if (r.armyError > 0 && r.gtVesInProgressArmy > 0) armyOverWithInProgress++;
            if (r.armyError < 0 && r.gtVesLostArmy > 0) armyUnderWithLost++;
            if (r.techError > 0 && r.gtVesInProgressTech > 0) techOverWithInProgress++;
            if (r.techError < 0 && r.gtVesLostTech > 0) techUnderWithLost++;
        }

        System.out.printf("Army: overcount replays with InProgress>0: %d%n", armyOverWithInProgress);
        System.out.printf("Army: undercount replays with Lost>0: %d%n", armyUnderWithLost);
        System.out.printf("Tech: overcount replays with InProgress>0: %d%n", techOverWithInProgress);
        System.out.printf("Tech: undercount replays with Lost>0: %d%n", techUnderWithLost);
        System.out.println();

        System.out.println("--- Alternative reference: EM vs GT (InProgress + Current) ---");
        int altArmyAbsErr = 0, altTechAbsErr = 0;
        int altArmyGtSum = 0, altTechGtSum = 0;
        for (var r : results) {
            int altGtArmy = r.gtVesArmy + r.gtVesInProgressArmy;
            int altGtTech = r.gtVesTech + r.gtVesInProgressTech;
            altArmyAbsErr += Math.abs(r.emVesArmy - altGtArmy);
            altTechAbsErr += Math.abs(r.emVesTech - altGtTech);
            altArmyGtSum += altGtArmy;
            altTechGtSum += altGtTech;
        }
        System.out.printf("Army: GT(IP+Cur)=%,d  EM=%,d  AbsErr=%,d  MAPE=%.1f%% (was %.1f%%)%n",
                altArmyGtSum,
                results.stream().mapToInt(r -> r.emVesArmy).sum(),
                altArmyAbsErr,
                altArmyGtSum > 0 ? altArmyAbsErr * 100.0 / altArmyGtSum : 0,
                results.stream().mapToInt(r -> r.gtVesArmy).sum() > 0
                        ? results.stream().mapToInt(r -> Math.abs(r.armyError)).sum() * 100.0
                        / results.stream().mapToInt(r -> r.gtVesArmy).sum() : 0);
        System.out.printf("Tech: GT(IP+Cur)=%,d  EM=%,d  AbsErr=%,d  MAPE=%.1f%% (was %.1f%%)%n",
                altTechGtSum,
                results.stream().mapToInt(r -> r.emVesTech).sum(),
                altTechAbsErr,
                altTechGtSum > 0 ? altTechAbsErr * 100.0 / altTechGtSum : 0,
                results.stream().mapToInt(r -> r.gtVesTech).sum() > 0
                        ? results.stream().mapToInt(r -> Math.abs(r.techError)).sum() * 100.0
                        / results.stream().mapToInt(r -> r.gtVesTech).sum() : 0);
        System.out.println();
    }


    @Test
    @EnabledIf("oracleExists")
    void spendingCategoryBreakdown() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        int[] checkpointsMin = {1, 3, 5, 7};
        int   tickLimit      = TICKS_PER_MINUTE * 8;

        // Per-checkpoint accumulators: [gtCurrent, gtInProgress, emTotal, emBuildings, emResearch, playerRuns]
        Map<Integer, long[]> accum = new TreeMap<>();
        for (int cp : checkpointsMin) {accum.put(cp, new long[6]);}

        // Per-checkpoint per-race accumulators: [gtCurrent, emTotal, emBuildings, emResearch]
        Map<Integer, Map<Race, long[]>> raceAccum = new TreeMap<>();
        for (int cp : checkpointsMin) {
            Map<Race, long[]> raceMap = new EnumMap<>(Race.class);
            for (Race r : Race.values()) {raceMap.put(r, new long[4]);}
            raceAccum.put(cp, raceMap);
        }

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                                                     EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS, RepContent.GAME_EVENTS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null || replay.trackerEvents == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent>   intents     = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race                race        = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    Map<Integer, int[]> gtInProgressPerCp = extractGtInProgressPerCheckpoint(
                            replay, playerId, checkpointsMin);

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
                    groundTruth.reset();
                    emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                    emulated.resetForHarness();

                    int cursor = 0;

                    for (int tick = 0; tick < tickLimit && !groundTruth.isComplete(); tick++) {
                        long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;
                        emulated.tick();
                        groundTruth.tick();

                        emulated.processDeferredTrainIntents();
                        emulated.processDeferredResearchIntents();

                        while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                            emulated.applyIntent(intents.get(cursor));
                            cursor++;
                        }

                        for (int cp : checkpointsMin) {
                            if (tick == cp * TICKS_PER_MINUTE - 1) {
                                GameState                          gtState = groundTruth.snapshot();
                                GameState                          emState = emulated.snapshot();
                                EmulatedGame.TechSpendingBreakdown bd      = emulated.techSpendingBreakdown();

                                int gtCur  = gtState.playerEconomy().vespeneUsedCurrentTechnology();
                                int emTech = emState.playerEconomy().vespeneUsedCurrentTechnology();
                                int emBldGas = bd.buildSpending().values().stream().mapToInt(a -> a[1]).sum()
                                               + bd.morphSpending().values().stream().mapToInt(a -> a[1]).sum()
                                               - bd.destroySubtractions().values().stream().mapToInt(a -> a[1]).sum();
                                int emResGas = bd.researchVespene();

                                int[] gtIp             = gtInProgressPerCp.getOrDefault(cp, new int[]{0, 0});
                                int   gtInProgressTech = gtIp[1];

                                long[] a = accum.get(cp);
                                a[0] += gtCur;
                                a[1] += gtInProgressTech;
                                a[2] += emTech;
                                a[3] += emBldGas;
                                a[4] += emResGas;
                                a[5]++;

                                long[] ra = raceAccum.get(cp).get(race);
                                ra[0] += gtCur;
                                ra[1] += emTech;
                                ra[2] += emBldGas;
                                ra[3] += emResGas;
                            }
                        }
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("\n=== Vespene Technology Spending Breakdown (#559) ===");
        System.out.printf("%-12s %12s %12s %12s %12s %12s %12s %12s %8s%n",
                          "Checkpoint", "GT Current", "GT InProg", "GT IP+Cur", "EM Total",
                          "EM Bldgs", "EM Research", "EM-GT Cur", "Runs");
        for (int cp : checkpointsMin) {
            long[] a = accum.get(cp);
            System.out.printf("%-12s %,12d %,12d %,12d %,12d %,12d %,12d %+,12d %8d%n",
                              cp + "-min", a[0], a[1], a[0] + a[1], a[2], a[3], a[4], a[2] - a[0], a[5]);
        }

        System.out.println("\n--- Hypothesis: research should be Army, not Tech ---");
        System.out.printf("%-12s %12s %12s %12s %12s%n",
                          "Checkpoint", "GT Current", "EM Bldg-Only", "AbsErr", "MAPE");
        for (int cp : checkpointsMin) {
            long[] a      = accum.get(cp);
            long   absErr = Math.abs(a[3] - a[0]);
            System.out.printf("%-12s %,12d %,12d %,12d %11.1f%%%n",
                              cp + "-min", a[0], a[3], absErr,
                              a[0] > 0 ? absErr * 100.0 / a[0] : 0);
        }

        System.out.println("\n--- Per-Race breakdown (all checkpoints summed) ---");
        System.out.printf("%-10s %12s %12s %12s %12s %12s%n",
                          "Race", "GT Current", "EM Total", "EM Bldgs", "EM Research", "EM-GT");
        for (Race race : List.of(Race.PROTOSS, Race.TERRAN, Race.ZERG)) {
            long gtSum = 0, emSum = 0, bldgSum = 0, resSum = 0;
            for (int cp : checkpointsMin) {
                long[] ra = raceAccum.get(cp).get(race);
                gtSum += ra[0];
                emSum += ra[1];
                bldgSum += ra[2];
                resSum += ra[3];
            }
            System.out.printf("%-10s %,12d %,12d %,12d %,12d %+,12d%n",
                              race, gtSum, emSum, bldgSum, resSum, emSum - gtSum);
        }
        System.out.println();
    }

    private Map<Integer, int[]> extractGtInProgressPerCheckpoint(
            Replay replay, int playerId, int[] checkpointsMin) {
        Map<Integer, int[]> result = new TreeMap<>();
        int[]               latest = {0, 0};

        for (var event : replay.trackerEvents.getEvents()) {
            if (!(event instanceof IPlayerStatsEvent pse)) {continue;}
            Integer pid = event.getPlayerId();
            if (pid == null || pid != playerId) {continue;}

            latest[0] = safe(pse.getGasUsedInProgressArmy());
            latest[1] = safe(pse.getGasUsedInProgressTech());

            for (int cp : checkpointsMin) {
                long cpLoop = (long) (cp * 60 * SC2Data.GAME_LOOPS_PER_SECOND);
                if (event.getLoop() <= cpLoop) {
                    result.put(cp, new int[]{latest[0], latest[1]});
                }
            }
        }
        return result;
    }

    private int[] extractGtInProgressAndLost(Replay replay, int playerId) {
        int vesInProgressArmy = 0, vesInProgressTech = 0;
        int vesLostArmy = 0, vesLostTech = 0;
        long cpLoop = (long) (CHECKPOINT_MIN * 60 * SC2Data.GAME_LOOPS_PER_SECOND);

        for (var event : replay.trackerEvents.getEvents()) {
            if (!(event instanceof IPlayerStatsEvent pse)) continue;
            Integer pid = event.getPlayerId();
            if (pid == null || pid != playerId) continue;
            if (event.getLoop() > cpLoop) break;

            vesInProgressArmy = safe(pse.getGasUsedInProgressArmy());
            vesInProgressTech = safe(pse.getGasUsedInProgressTech());
            vesLostArmy = safe(pse.getGasLostArmy());
            vesLostTech = safe(pse.getGasLostTech());
        }
        return new int[]{vesInProgressArmy, vesInProgressTech, vesLostArmy, vesLostTech};
    }

    private static int safe(Integer v) { return v != null ? v : 0; }
}
