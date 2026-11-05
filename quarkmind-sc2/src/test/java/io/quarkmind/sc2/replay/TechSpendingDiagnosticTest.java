package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Refs #533
 */
@Tag("diagnostic")
class TechSpendingDiagnosticTest {

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
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void techSpendingBreakdown() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().toList();
        }

        Map<Integer, Map<BuildingType, int[]>> buildAccum = new TreeMap<>();
        Map<Integer, Map<BuildingType, int[]>> morphAccum = new TreeMap<>();
        Map<Integer, Map<BuildingType, int[]>> destroyAccum = new TreeMap<>();
        Map<Integer, int[]> researchAccum = new TreeMap<>();
        Map<Integer, double[]> gtEmTotals = new TreeMap<>();
        Map<Integer, Map<BuildingType, int[]>> externalRecon = new TreeMap<>();

        for (int cp : CHECKPOINTS_MIN) {
            buildAccum.put(cp, new EnumMap<>(BuildingType.class));
            morphAccum.put(cp, new EnumMap<>(BuildingType.class));
            destroyAccum.put(cp, new EnumMap<>(BuildingType.class));
            researchAccum.put(cp, new int[2]);
            gtEmTotals.put(cp, new double[4]);
            externalRecon.put(cp, new EnumMap<>(BuildingType.class));
        }

        int totalPlayerRuns = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {
                continue;
            }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    Map<Integer, EmulatedGame.TechSpendingBreakdown> breakdowns = new HashMap<>();
                    Map<Integer, GameState> gtStates = new HashMap<>();
                    Map<Integer, PlayerEconomyStats> gtEcon = new HashMap<>();
                    Map<Integer, PlayerEconomyStats> emEcon = new HashMap<>();

                    runWithBreakdowns(groundTruth, intents, race,
                            breakdowns, gtStates, gtEcon, emEcon);

                    for (int cp : CHECKPOINTS_MIN) {
                        if (!breakdowns.containsKey(cp)) continue;
                        var bd = breakdowns.get(cp);

                        mergeMap(buildAccum.get(cp), bd.buildSpending());
                        mergeMap(morphAccum.get(cp), bd.morphSpending());
                        mergeMap(destroyAccum.get(cp), bd.destroySubtractions());
                        researchAccum.get(cp)[0] += bd.researchMinerals();
                        researchAccum.get(cp)[1] += bd.researchVespene();

                        double[] totals = gtEmTotals.get(cp);
                        totals[0] += gtEcon.get(cp).mineralsUsedCurrentTechnology();
                        totals[1] += emEcon.get(cp).mineralsUsedCurrentTechnology();
                        totals[2] += gtEcon.get(cp).vespeneUsedCurrentTechnology();
                        totals[3] += emEcon.get(cp).vespeneUsedCurrentTechnology();

                        for (Building b : gtStates.get(cp).myBuildings()) {
                            if (b.isComplete() && !EmulatedGame.isEconomyBuilding(b.type())) {
                                externalRecon.get(cp).merge(b.type(),
                                        new int[]{SC2Data.mineralCost(b.type()), SC2Data.gasCost(b.type())},
                                        (a, v) -> new int[]{a[0] + v[0], a[1] + v[1]});
                            }
                        }
                    }
                    totalPlayerRuns++;
                } catch (Exception e) {
                    // skip problematic replays
                }
            }
        }

        System.out.println("=== Technology Spending Diagnostic ===");
        System.out.printf("Replays: %d, Player runs: %d%n%n", replays.size(), totalPlayerRuns);

        for (int cp : CHECKPOINTS_MIN) {
            printCheckpoint(cp, buildAccum.get(cp), morphAccum.get(cp),
                    destroyAccum.get(cp), researchAccum.get(cp),
                    gtEmTotals.get(cp), externalRecon.get(cp));
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void classificationHypothesisTest() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        Set<BuildingType> DEFENSIVE = EnumSet.of(
                BuildingType.PHOTON_CANNON, BuildingType.SHIELD_BATTERY,
                BuildingType.BUNKER, BuildingType.SPINE_CRAWLER,
                BuildingType.SPORE_CRAWLER, BuildingType.MISSILE_TURRET);

        Set<BuildingType> SENSOR = EnumSet.of(BuildingType.SENSOR_TOWER);

        Set<BuildingType> ZERG_PRODUCTION = EnumSet.of(
                BuildingType.SPAWNING_POOL, BuildingType.BANELING_NEST,
                BuildingType.ROACH_WARREN, BuildingType.HYDRALISK_DEN,
                BuildingType.INFESTATION_PIT, BuildingType.NYDUS_NETWORK,
                BuildingType.NYDUS_CANAL);

        Set<BuildingType> PRODUCTION = EnumSet.of(
                BuildingType.GATEWAY, BuildingType.BARRACKS,
                BuildingType.FACTORY, BuildingType.STARPORT,
                BuildingType.ROBOTICS_FACILITY, BuildingType.STARGATE);

        record Hypothesis(String name, Set<BuildingType> armyBuildings) {}
        List<Hypothesis> hypotheses = List.of(
                new Hypothesis("H0: baseline (current isEconomy)", EnumSet.noneOf(BuildingType.class)),
                new Hypothesis("H1: defensive → army", DEFENSIVE),
                new Hypothesis("H2: H1 + sensor", EnumSet.copyOf(DEFENSIVE)),
                new Hypothesis("H3: H1 + zerg prod", EnumSet.copyOf(DEFENSIVE)),
                new Hypothesis("H4: H1 + all production", EnumSet.copyOf(DEFENSIVE)),
                new Hypothesis("H5: production only → army", PRODUCTION),
                new Hypothesis("H6: H1 + zerg prod + production", EnumSet.copyOf(DEFENSIVE))
                                             );
        ((EnumSet<BuildingType>) hypotheses.get(2).armyBuildings).addAll(SENSOR);
        ((EnumSet<BuildingType>) hypotheses.get(3).armyBuildings).addAll(ZERG_PRODUCTION);
        ((EnumSet<BuildingType>) hypotheses.get(4).armyBuildings).addAll(PRODUCTION);
        ((EnumSet<BuildingType>) hypotheses.get(6).armyBuildings).addAll(ZERG_PRODUCTION);
        ((EnumSet<BuildingType>) hypotheses.get(6).armyBuildings).addAll(PRODUCTION);

        int checkpoint     = 5;
        int checkpointTick = checkpoint * TICKS_PER_MINUTE - 1;
        int totalRuns      = 0;

        double[] sumAbsError = new double[hypotheses.size()];
        double[] sumMape     = new double[hypotheses.size()];
        double[] sumGtTech   = new double[hypotheses.size()];
        double[] sumRecon    = new double[hypotheses.size()];

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
                    groundTruth.reset();

                    GameState gtState = null;
                    for (int tick = 0; tick <= checkpointTick && !groundTruth.isComplete(); tick++) {
                        groundTruth.tick();
                        if (tick == checkpointTick) {gtState = groundTruth.snapshot();}
                    }
                    if (gtState == null) {continue;}

                    double gtTech = gtState.playerEconomy().mineralsUsedCurrentTechnology();
                    if (gtTech == 0) {continue;}

                    int researchCost = 0;
                    for (String upName : gtState.playerUpgrades()) {
                        UpgradeType ut = UpgradeType.fromPythonName(upName);
                        if (ut != null) {researchCost += SC2Data.upgradeMineralCost(ut);}
                    }

                    for (int h = 0; h < hypotheses.size(); h++) {
                        Set<BuildingType> armySet      = hypotheses.get(h).armyBuildings;
                        int               buildingCost = 0;
                        for (Building b : gtState.myBuildings()) {
                            if (!b.isComplete()) {continue;}
                            if (EmulatedGame.isEconomyBuilding(b.type())) {continue;}
                            if (armySet.contains(b.type())) {continue;}
                            buildingCost += SC2Data.mineralCost(b.type());
                        }
                        double reconTech = buildingCost + researchCost;
                        double absErr    = Math.abs(reconTech - gtTech);
                        double mape      = absErr / gtTech;
                        sumAbsError[h] += absErr;
                        sumMape[h] +=     mape;
                        sumGtTech[h] +=   gtTech;
                        sumRecon[h] +=    reconTech;
                    }
                    totalRuns++;
                } catch (Exception e) {
                    // skip
                }
            }
        }

        System.out.println("=== Classification Hypothesis Test (5-min checkpoint) ===");
        System.out.printf("Player runs: %d%n%n", totalRuns);
        System.out.printf("%-45s %12s %12s %8s %8s%n",
                          "Hypothesis", "GT Tech", "Recon Tech", "AbsErr", "MAPE");
        for (int h = 0; h < hypotheses.size(); h++) {
            System.out.printf("%-45s %,12.0f %,12.0f %,8.0f %7.1f%%%n",
                              hypotheses.get(h).name,
                              sumGtTech[h], sumRecon[h],
                              sumAbsError[h],
                              sumMape[h] / totalRuns * 100);
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void perTypeCostCorrelationTest() throws IOException {
        List<Path> replayList;
        try (var stream = Files.list(ORACLE_DIR)) {
            replayList = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                               .sorted().toList();
        }

        int checkpoint     = 5;
        int checkpointTick = checkpoint * TICKS_PER_MINUTE - 1;

        Map<BuildingType, double[]> typeStats         = new EnumMap<>(BuildingType.class);
        int                         totalRuns         = 0;
        double                      totalResidual     = 0;
        double                      totalResearchCost = 0;

        for (Path replayPath : replayList) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    groundTruth.reset();

                    GameState gtState = null;
                    for (int tick = 0; tick <= checkpointTick && !groundTruth.isComplete(); tick++) {
                        groundTruth.tick();
                        if (tick == checkpointTick) {gtState = groundTruth.snapshot();}
                    }
                    if (gtState == null) {continue;}

                    double gtTech = gtState.playerEconomy().mineralsUsedCurrentTechnology();

                    int researchCost = 0;
                    for (String upName : gtState.playerUpgrades()) {
                        UpgradeType ut = UpgradeType.fromPythonName(upName);
                        if (ut != null) {researchCost += SC2Data.upgradeMineralCost(ut);}
                    }

                    int                        totalBuildingCost = 0;
                    Map<BuildingType, Integer> perTypeCost       = new EnumMap<>(BuildingType.class);
                    for (Building b : gtState.myBuildings()) {
                        if (!b.isComplete()) {continue;}
                        if (EmulatedGame.isEconomyBuilding(b.type())) {continue;}
                        int cost = SC2Data.mineralCost(b.type());
                        totalBuildingCost += cost;
                        perTypeCost.merge(b.type(), cost, Integer::sum);
                    }

                    double reconTech = totalBuildingCost + researchCost;
                    double residual  = reconTech - gtTech;

                    for (var entry : perTypeCost.entrySet()) {
                        typeStats.computeIfAbsent(entry.getKey(), k -> new double[]{0, 0, 0, 0});
                        double[] stats = typeStats.get(entry.getKey());
                        stats[0] += entry.getValue();
                        stats[1] += 1;
                        stats[2] += residual;
                        stats[3] += entry.getValue() * (residual > 0 ? 1 : 0);
                    }

                    totalResidual += residual;
                    totalResearchCost += researchCost;
                    totalRuns++;
                } catch (Exception e) {
                    // skip
                }
            }
        }

        System.out.println("=== Per-Type Cost Correlation with Residual (5-min) ===");
        System.out.printf("Player runs: %d, Total residual: %,.0f, Total research: %,.0f%n%n",
                          totalRuns, totalResidual, totalResearchCost);
        System.out.printf("%-25s %8s %12s %12s %12s%n",
                          "BuildingType", "Count", "TotalCost", "AvgResidual", "CostInOverruns");
        typeStats.entrySet().stream()
                 .sorted((a, b) -> Double.compare(b.getValue()[3], a.getValue()[3]))
                 .forEach(e -> {
                     double[] s           = e.getValue();
                     double   avgResidual = s[1] > 0 ? s[2] / s[1] : 0;
                     System.out.printf("%-25s %8.0f %,12.0f %,12.1f %,12.0f%n",
                                       e.getKey(), s[1], s[0], avgResidual, s[3]);
                 });
    }


    @Test
    @EnabledIf("oracleExists")
    void fullSpendingFieldsTest() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        double[][] accum     = new double[CHECKPOINTS_MIN.length][9];
        int        totalRuns = 0;

        for (Path replayPath : replays) {
            hu.scelight.sc2.rep.model.Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                                                     EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.trackerEvents == null || replay.details == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            var events = replay.trackerEvents.getEvents();

            for (int playerId = 1; playerId <= 2; playerId++) {
                int[] lastInProgressTech = new int[CHECKPOINTS_MIN.length];
                int[] lastCurrentTech    = new int[CHECKPOINTS_MIN.length];
                int[] lastLostTech       = new int[CHECKPOINTS_MIN.length];
                int[] lastInProgressArmy = new int[CHECKPOINTS_MIN.length];
                int[] lastCurrentArmy    = new int[CHECKPOINTS_MIN.length];
                int[] lastInProgressEcon = new int[CHECKPOINTS_MIN.length];
                int[] lastCurrentEcon    = new int[CHECKPOINTS_MIN.length];
                int[] lastLostArmy       = new int[CHECKPOINTS_MIN.length];
                int[] lastLostEcon       = new int[CHECKPOINTS_MIN.length];

                for (var event : events) {
                    if (!(event instanceof hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent pse)) {
                        continue;
                    }
                    Integer pid = event.getPlayerId();
                    if (pid == null || pid != playerId) {continue;}

                    long loop = event.getLoop();
                    for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
                        long cpLoop = (long) CHECKPOINTS_MIN[i] * 60 * (long) SC2Data.GAME_LOOPS_PER_SECOND;
                        if (loop <= cpLoop) {
                            lastInProgressTech[i] = safe(pse.getMinsUsedInProgressTech());
                            lastCurrentTech[i]    = safe(pse.getMinsUsedInCurrentTech());
                            lastLostTech[i]       = safe(pse.getMinsLostTech());
                            lastInProgressArmy[i] = safe(pse.getMinsUsedInProgressArmy());
                            lastCurrentArmy[i]    = safe(pse.getMinsUsedInCurrentArmy());
                            lastInProgressEcon[i] = safe(pse.getMinsUsedInProgressEcon());
                            lastCurrentEcon[i]    = safe(pse.getMinsUsedInCurrentEcon());
                            lastLostArmy[i]       = safe(pse.getMinsLostArmy());
                            lastLostEcon[i]       = safe(pse.getMinsLostEcon());
                        }
                    }
                }

                for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
                    accum[i][0] += lastInProgressTech[i];
                    accum[i][1] += lastCurrentTech[i];
                    accum[i][2] += lastLostTech[i];
                    accum[i][3] += lastInProgressArmy[i];
                    accum[i][4] += lastCurrentArmy[i];
                    accum[i][5] += lastInProgressEcon[i];
                    accum[i][6] += lastCurrentEcon[i];
                    accum[i][7] += lastLostArmy[i];
                    accum[i][8] += lastLostEcon[i];
                }
                totalRuns++;
            }
        }

        System.out.println("=== Full SC2 Spending Fields (from PlayerStatsEvent) ===");
        System.out.printf("Player runs: %d%n%n", totalRuns);
        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            int cp = CHECKPOINTS_MIN[i];
            System.out.printf("--- %d-minute checkpoint ---%n", cp);
            System.out.printf("%-35s %12s %12s %12s %12s%n",
                              "Category", "InProgress", "Current", "Lost", "Total");
            System.out.printf("%-35s %,12.0f %,12.0f %,12.0f %,12.0f%n", "Technology",
                              accum[i][0], accum[i][1], accum[i][2],
                              accum[i][0] + accum[i][1] + accum[i][2]);
            System.out.printf("%-35s %,12.0f %,12.0f %,12.0f %,12.0f%n", "Army",
                              accum[i][3], accum[i][4], accum[i][7],
                              accum[i][3] + accum[i][4] + accum[i][7]);
            System.out.printf("%-35s %,12.0f %,12.0f %,12.0f %,12.0f%n", "Economy",
                              accum[i][5], accum[i][6], accum[i][8],
                              accum[i][5] + accum[i][6] + accum[i][8]);
            System.out.printf("%-35s %,12.0f %,12.0f %,12.0f %,12.0f%n%n", "Grand total",
                              accum[i][0] + accum[i][3] + accum[i][5],
                              accum[i][1] + accum[i][4] + accum[i][6],
                              accum[i][2] + accum[i][7] + accum[i][8],
                              accum[i][0] + accum[i][1] + accum[i][2] +
                              accum[i][3] + accum[i][4] + accum[i][7] +
                              accum[i][5] + accum[i][6] + accum[i][8]);
        }
    }

    private static int safe(Integer v) {return v != null ? v : 0;}

    private void runWithBreakdowns(
            ReplaySimulatedGame groundTruth, List<TimedIntent> intents, Race race,
            Map<Integer, EmulatedGame.TechSpendingBreakdown> breakdowns,
            Map<Integer, GameState> gtStates,
            Map<Integer, PlayerEconomyStats> gtEcon,
            Map<Integer, PlayerEconomyStats> emEcon) {

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
        groundTruth.reset();
        emulated.setMineralPatchResources(groundTruth.getMineralPatches());
        emulated.resetForHarness();

        Set<String> reactoredTags = groundTruth.reactoredBuildingTags();
        int cursor = 0;
        Map<String, BuildingType> injectedBuildings = new HashMap<>();

        Set<Integer> checkpointTicks = new HashSet<>();
        for (int cp : CHECKPOINTS_MIN) {
            checkpointTicks.add(cp * TICKS_PER_MINUTE - 1);
        }

        for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

            emulated.tick();
            groundTruth.tick();

            GameState gt = groundTruth.snapshot();
            syncBuildings(emulated, gt, injectedBuildings, reactoredTags);

            emulated.processDeferredTrainIntents();
            emulated.processDeferredResearchIntents();

            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                emulated.applyIntent(intents.get(cursor));
                cursor++;
            }

            if (checkpointTicks.contains(tick)) {
                int cpMin = (tick + 1) / TICKS_PER_MINUTE;
                breakdowns.put(cpMin, emulated.techSpendingBreakdown());
                gtStates.put(cpMin, gt);
                GameState em = emulated.snapshot();
                gtEcon.put(cpMin, gt.playerEconomy());
                emEcon.put(cpMin, em.playerEconomy());
            }
        }
    }

    private static void syncBuildings(EmulatedGame emulated, GameState gt,
                                       Map<String, BuildingType> injectedBuildings, Set<String> reactoredTags) {
        Set<String> gtTags = new HashSet<>();
        for (Building gtBuilding : gt.myBuildings()) {
            gtTags.add(gtBuilding.tag());
            if (!injectedBuildings.containsKey(gtBuilding.tag())) {
                emulated.injectReplayBuilding(gtBuilding);
                injectedBuildings.put(gtBuilding.tag(), gtBuilding.type());
            } else {
                emulated.syncReplayBuilding(gtBuilding);
            }
            if (gtBuilding.isComplete() && reactoredTags.contains(gtBuilding.tag())) {
                emulated.markBuildingReactored(gtBuilding.tag());
            }
        }
        var destroyed = new ArrayList<>(injectedBuildings.keySet());
        destroyed.removeAll(gtTags);
        for (String tag : destroyed) {
            BuildingType type = injectedBuildings.remove(tag);
            emulated.removeDestroyedBuilding(tag, type);
        }
    }

    private static void mergeMap(Map<BuildingType, int[]> target, Map<BuildingType, int[]> source) {
        source.forEach((type, costs) ->
                target.merge(type, new int[]{costs[0], costs[1]},
                        (a, b) -> new int[]{a[0] + b[0], a[1] + b[1]}));
    }

    private void printCheckpoint(int cpMin,
                                  Map<BuildingType, int[]> build,
                                  Map<BuildingType, int[]> morph,
                                  Map<BuildingType, int[]> destroy,
                                  int[] research,
                                  double[] gtEmTotals,
                                  Map<BuildingType, int[]> extRecon) {

        int buildM = build.values().stream().mapToInt(a -> a[0]).sum();
        int buildV = build.values().stream().mapToInt(a -> a[1]).sum();
        int morphM = morph.values().stream().mapToInt(a -> a[0]).sum();
        int morphV = morph.values().stream().mapToInt(a -> a[1]).sum();
        int destM = destroy.values().stream().mapToInt(a -> a[0]).sum();
        int destV = destroy.values().stream().mapToInt(a -> a[1]).sum();
        int reconM = extRecon.values().stream().mapToInt(a -> a[0]).sum();
        int reconV = extRecon.values().stream().mapToInt(a -> a[1]).sum();

        System.out.printf("=== %d-minute checkpoint ===%n", cpMin);
        System.out.printf("%-30s %12s %12s%n", "Source", "Minerals", "Vespene");
        System.out.printf("%-30s %,12d %,12d%n", "Build (non-economy)", buildM, buildV);
        System.out.printf("%-30s %,12d %,12d%n", "Research", research[0], research[1]);
        System.out.printf("%-30s %,12d %,12d%n", "Morph (non-economy)", morphM, morphV);
        System.out.printf("%-30s %,12d %,12d%n", "Destroy (subtracted)", -destM, -destV);
        System.out.println("---");
        System.out.printf("%-30s %,12d %,12d%n", "EM computed total",
                buildM + research[0] + morphM - destM,
                buildV + research[1] + morphV - destV);
        System.out.printf("%-30s %,12.0f %,12.0f%n", "EM reported total", gtEmTotals[1], gtEmTotals[3]);
        System.out.printf("%-30s %,12.0f %,12.0f%n", "GT total", gtEmTotals[0], gtEmTotals[2]);
        System.out.printf("%-30s %,12.0f %,12.0f%n", "Gap (EM - GT)",
                gtEmTotals[1] - gtEmTotals[0], gtEmTotals[3] - gtEmTotals[2]);

        System.out.println();
        System.out.println("--- External cross-check (GT alive non-economy buildings × cost) ---");
        System.out.printf("%-30s %,12d %,12d%n", "Reconstructed building cost", reconM, reconV);
        System.out.printf("%-30s %,12d %,12d%n", "Recon + research",
                reconM + research[0], reconV + research[1]);
        System.out.printf("%-30s %,12.0f %,12.0f%n", "GT total", gtEmTotals[0], gtEmTotals[2]);
        System.out.printf("%-30s %,12.0f %,12.0f%n", "Recon delta (recon+research - GT)",
                (reconM + research[0]) - gtEmTotals[0],
                (reconV + research[1]) - gtEmTotals[2]);

        System.out.println();
        System.out.println("--- Per-building-type build spending (top 15 by minerals) ---");
        System.out.printf("%-25s %8s %12s %12s%n", "BuildingType", "Events", "Minerals", "Vespene");
        build.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(15)
                .forEach(e -> {
                    int count = e.getValue()[0] / Math.max(1, SC2Data.mineralCost(e.getKey()));
                    System.out.printf("%-25s %8d %,12d %,12d%n",
                            e.getKey(), count, e.getValue()[0], e.getValue()[1]);
                });

        if (!morph.isEmpty()) {
            System.out.println();
            System.out.println("--- Per-building-type morph spending ---");
            System.out.printf("%-25s %12s %12s%n", "BuildingType", "Minerals", "Vespene");
            morph.forEach((type, costs) ->
                    System.out.printf("%-25s %,12d %,12d%n", type, costs[0], costs[1]));
        }

        if (!destroy.isEmpty()) {
            System.out.println();
            System.out.println("--- Per-building-type destruction subtractions ---");
            System.out.printf("%-25s %12s %12s%n", "BuildingType", "Minerals", "Vespene");
            destroy.forEach((type, costs) ->
                    System.out.printf("%-25s %,12d %,12d%n", type, costs[0], costs[1]));
        }

        System.out.println();
    }
}
