package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import io.quarkmind.domain.BuildingType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Tag("report")
class OracleAccuracyBaselineTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path STRIPPED_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final int LOOPS_PER_MINUTE = 1344;
    private static final int STATS_INTERVAL = 160;
    private static final int[] CHECKPOINT_MINUTES = {1, 3, 5, 7};

    private static final Set<String> BUILDING_NAMES = Arrays.stream(BuildingType.values())
        .map(StrippedReplayFeatureExtractor::buildingTypeToPythonName)
        .filter(n -> n != null)
        .collect(Collectors.toSet());

    private static final Set<String> BUILDING_NAMES_EXTENDED;
    static {
        var names = new java.util.HashSet<>(BUILDING_NAMES);
        names.add("WarpGate");
        names.add("SupplyDepotLowered");
        names.add("RefineryRich");
        names.add("ExtractorRich");
        names.add("BarracksFlying");
        names.add("FactoryFlying");
        names.add("StarportFlying");
        names.add("CommandCenterFlying");
        names.add("OrbitalCommandFlying");
        BUILDING_NAMES_EXTENDED = Set.copyOf(names);
    }

    private static final List<String> COSMETIC_PREFIXES = List.of(
        "RewardDance", "Spray", "GhostAlternate", "GameHeartActive");

    private static final Set<String> COMBAT_SPAWN_UNITS = Set.of(
        "Broodling", "BroodlingEscort", "Locust", "InfestorTerran",
        "Changeling", "ChangelingMarine", "ChangelingMarineShield",
        "ChangelingZealot", "ChangelingZergling", "ChangelingZerglingWings",
        "ParasiticBombDummy", "KD8Charge", "AutoTurret", "PointDefenseDrone");

    private static final Set<String> TIMER_AUTOSPAWN_UNITS = Set.of("Larva", "Interceptor");

    private static final Set<String> STRUCTURAL_MORPH_BUILDINGS = Set.of("WarpGate");

    private static final String[] ECONOMY_FIELDS = {
        "scoreValueMineralsCurrent", "scoreValueVespeneCurrent",
        "scoreValueMineralsCollectionRate", "scoreValueVespeneCollectionRate",
        "scoreValueFoodMade", "scoreValueFoodUsed",
        "scoreValueWorkersActiveCount",
        "scoreValueMineralsUsedCurrentArmy", "scoreValueMineralsUsedCurrentEconomy",
        "scoreValueMineralsUsedCurrentTechnology",
        "scoreValueVespeneUsedCurrentArmy", "scoreValueVespeneUsedCurrentEconomy",
        "scoreValueVespeneUsedCurrentTechnology"
    };

    static boolean oracleAndOriginalsExist() {
        return directoryHasReplays(ORACLE_DIR) && directoryHasReplays(STRIPPED_DIR);
    }

    private static boolean directoryHasReplays(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleAndOriginalsExist")
    void oracleAccuracyBaseline() throws Exception {
        var extractor = new StrippedReplayFeatureExtractor();
        var stats = new BaselineStats();

        List<Path> oracleReplays;
        try (var stream = Files.list(ORACLE_DIR)) {
            oracleReplays = stream
                .filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted()
                .toList();
        }

        for (Path oracleReplay : oracleReplays) {
            Path stripped = STRIPPED_DIR.resolve(oracleReplay.getFileName());
            if (!Files.exists(stripped)) continue;
            processReplay(extractor, oracleReplay, stripped, stats);
        }

        printReport(stats);
        writeMarkdownReport(stats);
    }

    private void processReplay(StrippedReplayFeatureExtractor extractor,
                               Path oracleReplay, Path strippedReplay,
                               BaselineStats stats) {
        stats.totalReplays++;

        Map<String, Object> javaJson;
        try {
            javaJson = extractor.extract(strippedReplay);
        } catch (Exception e) {
            System.out.printf("SKIP %s — extract failed: %s%n",
                oracleReplay.getFileName(), e.getMessage());
            return;
        }

        Replay oracleRep = RepParserEngine.parseReplay(oracleReplay,
            EnumSet.of(RepContent.TRACKER_EVENTS));
        if (oracleRep == null || oracleRep.trackerEvents == null) {
            System.out.printf("SKIP %s — no tracker events%n", oracleReplay.getFileName());
            return;
        }

        Map<String, Integer> oracleBorn = new TreeMap<>();
        Map<String, Integer> oracleInit = new TreeMap<>();
        Map<String, Integer> oracleUpgrade = new TreeMap<>();
        Map<Integer, Map<Integer, Map<String, Integer>>> oraclePlayerStats = new HashMap<>();

        for (Event raw : oracleRep.trackerEvents.getEvents()) {
            switch (raw.getId()) {
                case ITrackerEvents.ID_UNIT_BORN -> {
                    IBaseUnitEvent born = (IBaseUnitEvent) raw;
                    if (born.getControlPlayerId() == null || born.getControlPlayerId() == 0) continue;
                    if (born.getLoop() == 0) continue;
                    String key = "P" + born.getControlPlayerId() + ":" + born.getUnitTypeName();
                    oracleBorn.merge(key, 1, Integer::sum);
                }
                case ITrackerEvents.ID_UNIT_INIT -> {
                    IBaseUnitEvent init = (IBaseUnitEvent) raw;
                    if (init.getControlPlayerId() == null || init.getControlPlayerId() == 0) continue;
                    String key = "P" + init.getControlPlayerId() + ":" + init.getUnitTypeName();
                    oracleInit.merge(key, 1, Integer::sum);
                }
                case ITrackerEvents.ID_UPGRADE -> {
                    IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                    if (upgrade.getPlayerId() == null) continue;
                    String key = "P" + upgrade.getPlayerId() + ":" + upgrade.getUpgradeTypeName();
                    oracleUpgrade.merge(key, 1, Integer::sum);
                }
                case ITrackerEvents.ID_PLAYER_STATS -> {
                    IPlayerStatsEvent ps = (IPlayerStatsEvent) raw;
                    if (ps.getPlayerId() == null || ps.getPlayerId() == 0) continue;
                    int loop = ps.getLoop();
                    var fieldValues = new LinkedHashMap<String, Integer>();
                    fieldValues.put("scoreValueMineralsCurrent", ps.getMineralsCurrent() * 1000);
                    fieldValues.put("scoreValueVespeneCurrent", ps.getGasCurrent() * 1000);
                    fieldValues.put("scoreValueMineralsCollectionRate", ps.getMinsCollRate() * 1000);
                    fieldValues.put("scoreValueVespeneCollectionRate", ps.getGasCollRate() * 1000);
                    fieldValues.put("scoreValueFoodMade", ps.getFoodMade());
                    fieldValues.put("scoreValueFoodUsed", ps.getFoodUsed());
                    fieldValues.put("scoreValueWorkersActiveCount", ps.getWorkersActiveCount() * 1000);
                    fieldValues.put("scoreValueMineralsUsedCurrentArmy", ps.getMinsUsedInCurrentArmy() * 1000);
                    fieldValues.put("scoreValueMineralsUsedCurrentEconomy", ps.getMinsUsedInCurrentEcon() * 1000);
                    fieldValues.put("scoreValueMineralsUsedCurrentTechnology", ps.getMinsUsedInCurrentTech() * 1000);
                    fieldValues.put("scoreValueVespeneUsedCurrentArmy", ps.getGasUsedInCurrentArmy() * 1000);
                    fieldValues.put("scoreValueVespeneUsedCurrentEconomy", ps.getGasUsedInCurrentEcon() * 1000);
                    fieldValues.put("scoreValueVespeneUsedCurrentTechnology", ps.getGasUsedInCurrentTech() * 1000);
                    oraclePlayerStats.computeIfAbsent(ps.getPlayerId(), k -> new TreeMap<>())
                        .put(loop, fieldValues);
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> javaEvents =
            (List<Map<String, Object>>) javaJson.get("trackerEvents");

        Map<String, Integer> javaBorn = new TreeMap<>();
        Map<String, Integer> javaInit = new TreeMap<>();
        Map<String, Integer> javaUpgrade = new TreeMap<>();
        Map<Integer, Map<Integer, Map<String, Object>>> javaPlayerStats = new HashMap<>();

        for (Map<String, Object> e : javaEvents) {
            String evtType = (String) e.get("evtTypeName");
            int pid = e.containsKey("controlPlayerId")
                ? ((Number) e.get("controlPlayerId")).intValue()
                : ((Number) e.getOrDefault("playerId", 0)).intValue();
            if (pid == 0) continue;

            switch (evtType) {
                case "UnitBorn" -> {
                    String key = "P" + pid + ":" + e.get("unitTypeName");
                    javaBorn.merge(key, 1, Integer::sum);
                }
                case "UnitInit" -> {
                    String key = "P" + pid + ":" + e.get("unitTypeName");
                    javaInit.merge(key, 1, Integer::sum);
                }
                case "Upgrade" -> {
                    String key = "P" + pid + ":" + e.get("upgradeTypeName");
                    javaUpgrade.merge(key, 1, Integer::sum);
                }
                case "PlayerStats" -> {
                    int loop = ((Number) e.get("loop")).intValue();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> statsMap = (Map<String, Object>) e.get("stats");
                    javaPlayerStats.computeIfAbsent(pid, k -> new TreeMap<>())
                        .put(loop, statsMap);
                }
            }
        }

        // --- Compare units (UnitBorn, non-building) — classify by tier ---
        int replayOracleUnits = 0, replayJavaUnits = 0;
        for (var entry : oracleBorn.entrySet()) {
            String unitName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            if (BUILDING_NAMES_EXTENDED.contains(unitName)) continue;
            int oracle = entry.getValue();
            int java = javaBorn.getOrDefault(entry.getKey(), 0);
            replayOracleUnits += oracle;
            replayJavaUnits += java;
            addToDivergence(stats.unitBornDivergence, entry.getKey(), oracle, java);

            if (COMBAT_SPAWN_UNITS.contains(unitName)) {
                addToDivergence(stats.t3UnitDivergence, entry.getKey(), oracle, java);
                stats.t3OracleUnits += oracle;
                stats.t3JavaUnits += java;
            } else if (TIMER_AUTOSPAWN_UNITS.contains(unitName)) {
                addToDivergence(stats.t2UnitDivergence, entry.getKey(), oracle, java);
                stats.t2OracleUnits += oracle;
                stats.t2JavaUnits += java;
            } else {
                addToDivergence(stats.t1UnitDivergence, entry.getKey(), oracle, java);
                stats.t1OracleUnits += oracle;
                stats.t1JavaUnits += java;
            }
        }
        for (var entry : javaBorn.entrySet()) {
            String unitName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            if (BUILDING_NAMES_EXTENDED.contains(unitName)) continue;
            if (!oracleBorn.containsKey(entry.getKey())) {
                int java = entry.getValue();
                replayJavaUnits += java;
                addToDivergence(stats.unitBornDivergence, entry.getKey(), 0, java);
                if (COMBAT_SPAWN_UNITS.contains(unitName)) {
                    addToDivergence(stats.t3UnitDivergence, entry.getKey(), 0, java);
                    stats.t3JavaUnits += java;
                } else if (TIMER_AUTOSPAWN_UNITS.contains(unitName)) {
                    addToDivergence(stats.t2UnitDivergence, entry.getKey(), 0, java);
                    stats.t2JavaUnits += java;
                } else {
                    addToDivergence(stats.t1UnitDivergence, entry.getKey(), 0, java);
                    stats.t1JavaUnits += java;
                }
            }
        }
        stats.totalOracleUnits += replayOracleUnits;
        stats.totalJavaUnits += replayJavaUnits;

        // --- Compare buildings (UnitInit, building types) — classify by tier ---
        int replayOracleBuildings = 0, replayJavaBuildings = 0;
        for (var entry : oracleInit.entrySet()) {
            String unitName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            if (!BUILDING_NAMES_EXTENDED.contains(unitName)) continue;
            int oracle = entry.getValue();
            int java = javaInit.getOrDefault(entry.getKey(), 0);
            replayOracleBuildings += oracle;
            replayJavaBuildings += java;
            addToDivergence(stats.buildingDivergence, entry.getKey(), oracle, java);
            if (STRUCTURAL_MORPH_BUILDINGS.contains(unitName)) {
                addToDivergence(stats.t4BuildingDivergence, entry.getKey(), oracle, java);
                stats.t4OracleBuildings += oracle;
                stats.t4JavaBuildings += java;
            } else {
                addToDivergence(stats.t1BuildingDivergence, entry.getKey(), oracle, java);
                stats.t1OracleBuildings += oracle;
                stats.t1JavaBuildings += java;
            }
        }
        for (var entry : javaInit.entrySet()) {
            String unitName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            if (!BUILDING_NAMES_EXTENDED.contains(unitName)) continue;
            if (!oracleInit.containsKey(entry.getKey())) {
                int java = entry.getValue();
                replayJavaBuildings += java;
                addToDivergence(stats.buildingDivergence, entry.getKey(), 0, java);
                if (STRUCTURAL_MORPH_BUILDINGS.contains(unitName)) {
                    addToDivergence(stats.t4BuildingDivergence, entry.getKey(), 0, java);
                    stats.t4JavaBuildings += java;
                } else {
                    addToDivergence(stats.t1BuildingDivergence, entry.getKey(), 0, java);
                    stats.t1JavaBuildings += java;
                }
            }
        }
        stats.totalOracleBuildings += replayOracleBuildings;
        stats.totalJavaBuildings += replayJavaBuildings;

        // --- Compare upgrades ---
        int replayOracleUpgradeCount = 0, replayMatchedUpgradeCount = 0;
        for (var entry : oracleUpgrade.entrySet()) {
            int oracle = entry.getValue();
            int java = javaUpgrade.getOrDefault(entry.getKey(), 0);
            replayOracleUpgradeCount += oracle;
            replayMatchedUpgradeCount += Math.min(java, oracle);
            addToDivergence(stats.upgradeDivergence, entry.getKey(), oracle, java);
            stats.totalOracleUpgrades += oracle;
            stats.totalJavaUpgrades += java;
            String upgradeName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            boolean cosmetic = COSMETIC_PREFIXES.stream().anyMatch(upgradeName::startsWith);
            if (!cosmetic) {
                stats.gameplayOracleUpgrades += oracle;
                stats.gameplayMatchedUpgrades += Math.min(java, oracle);
            }
        }

        // --- Compare economy (PlayerStats) ---
        double replayEconomyMape5min = Double.NaN;
        for (int playerId : oraclePlayerStats.keySet()) {
            Map<Integer, Map<String, Integer>> oracleByLoop = oraclePlayerStats.get(playerId);
            Map<Integer, Map<String, Object>> javaByLoop = javaPlayerStats.getOrDefault(playerId, Map.of());
            for (int ci = 0; ci < CHECKPOINT_MINUTES.length; ci++) {
                int targetLoop = CHECKPOINT_MINUTES[ci] * LOOPS_PER_MINUTE;
                int alignedLoop = (targetLoop / STATS_INTERVAL) * STATS_INTERVAL;
                Map<String, Integer> oracleFields = oracleByLoop.get(alignedLoop);
                if (oracleFields == null) continue;
                Map<String, Object> javaFields = javaByLoop.get(alignedLoop);
                stats.economyReplayCount[ci]++;
                for (int fi = 0; fi < ECONOMY_FIELDS.length; fi++) {
                    String field = ECONOMY_FIELDS[fi];
                    int oracleVal = oracleFields.getOrDefault(field, 0);
                    double error;
                    if (javaFields == null) {
                        error = 1.0;
                    } else {
                        double javaVal = ((Number) javaFields.getOrDefault(field, 0)).doubleValue();
                        if (oracleVal == 0) {
                            error = (javaVal == 0) ? 0.0 : 1.0;
                        } else {
                            error = Math.abs(javaVal - oracleVal) / Math.abs(oracleVal);
                        }
                    }
                    stats.economyErrors[fi][ci].add(error);
                    if (CHECKPOINT_MINUTES[ci] == 5 && fi == 0 && playerId == 1) {
                        replayEconomyMape5min = error;
                    }
                }
            }
        }

        double replayUnitAcc = replayOracleUnits > 0
            ? 100.0 * Math.min(replayJavaUnits, replayOracleUnits) / replayOracleUnits : 100.0;
        double replayBuildingAcc = replayOracleBuildings > 0
            ? 100.0 * Math.min(replayJavaBuildings, replayOracleBuildings) / replayOracleBuildings : 100.0;
        double replayUpgradeAcc = replayOracleUpgradeCount > 0
            ? 100.0 * replayMatchedUpgradeCount / replayOracleUpgradeCount : 100.0;
        stats.replaySummaries.add(new ReplaySummary(
            oracleReplay.getFileName().toString(),
            replayUnitAcc, replayBuildingAcc, replayUpgradeAcc,
            Double.isNaN(replayEconomyMape5min) ? 0.0 : replayEconomyMape5min * 100));
        stats.processedReplays++;
    }

    private static void addToDivergence(Map<String, int[]> map, String key, int oracle, int java) {
        map.computeIfAbsent(key, k -> new int[2]);
        map.get(key)[0] += oracle;
        map.get(key)[1] += java;
    }

    private void printReport(BaselineStats stats) {
        System.out.printf("%n========================================%n");
        System.out.printf("Oracle Accuracy Baseline — 4.9.3 (%d replays)%n", stats.processedReplays);
        System.out.printf("========================================%n");

        double unitAcc = accPct(stats.totalJavaUnits, stats.totalOracleUnits);
        double buildingAcc = accPct(stats.totalJavaBuildings, stats.totalOracleBuildings);
        double upgradeAcc = stats.gameplayOracleUpgrades > 0
            ? 100.0 * stats.gameplayMatchedUpgrades / stats.gameplayOracleUpgrades : 100.0;
        double economyMape5min = computeEconomyMape(stats, 5);

        System.out.printf("%n--- Aggregate Summary ---%n");
        System.out.printf("%-12s %8s %s%n", "Category", "Accuracy", "Notes");
        System.out.printf("%-12s %7.1f%% count match across %d replays%n", "Units", unitAcc, stats.processedReplays);
        System.out.printf("%-12s %7.1f%% count match, building types only%n", "Buildings", buildingAcc);
        System.out.printf("%-12s %7.1f%% gameplay only (excl. cosmetics)%n", "Upgrades", upgradeAcc);
        System.out.printf("%-12s %7.1f%% mean MAPE across 13 fields at 5min%n", "Economy", economyMape5min);

        double t1UnitAcc = accPct(stats.t1JavaUnits, stats.t1OracleUnits);
        double t1BuildingAcc = accPct(stats.t1JavaBuildings, stats.t1OracleBuildings);
        double t2UnitAcc = accPct(stats.t2JavaUnits, stats.t2OracleUnits);

        System.out.printf("%n--- Tier Breakdown ---%n");
        System.out.printf("T1 Commanded Units:     %7.1f%% (oracle=%d java=%d)%n", t1UnitAcc, stats.t1OracleUnits, stats.t1JavaUnits);
        System.out.printf("T1 Commanded Buildings: %7.1f%% (oracle=%d java=%d)%n", t1BuildingAcc, stats.t1OracleBuildings, stats.t1JavaBuildings);
        System.out.printf("T2 Auto-spawn Units:    %7.1f%% (oracle=%d java=%d)%n", t2UnitAcc, stats.t2OracleUnits, stats.t2JavaUnits);
        System.out.printf("T3 Combat Spawn:        excluded (oracle=%d java=%d)%n", stats.t3OracleUnits, stats.t3JavaUnits);
        System.out.printf("T4 Structural Morph:    excluded (oracle=%d java=%d)%n", stats.t4OracleBuildings, stats.t4JavaBuildings);

        int tierSumOracleUnits = stats.t1OracleUnits + stats.t2OracleUnits + stats.t3OracleUnits;
        int tierSumJavaUnits = stats.t1JavaUnits + stats.t2JavaUnits + stats.t3JavaUnits;
        int tierSumOracleBuildings = stats.t1OracleBuildings + stats.t4OracleBuildings;
        int tierSumJavaBuildings = stats.t1JavaBuildings + stats.t4JavaBuildings;
        System.out.printf("%nRegression check: units oracle %d==%d java %d==%d | buildings oracle %d==%d java %d==%d%n",
            stats.totalOracleUnits, tierSumOracleUnits,
            stats.totalJavaUnits, tierSumJavaUnits,
            stats.totalOracleBuildings, tierSumOracleBuildings,
            stats.totalJavaBuildings, tierSumJavaBuildings);

        System.out.printf("%n--- T1 Commanded Units: Top Divergences ---%n");
        printDivergenceTable(stats.t1UnitDivergence, 10);
        System.out.printf("%n--- T1 Commanded Buildings: Top Divergences ---%n");
        printDivergenceTable(stats.t1BuildingDivergence, 10);
        System.out.printf("%n--- T2 Auto-spawn: Divergences ---%n");
        printDivergenceTable(stats.t2UnitDivergence, 10);
        System.out.printf("%n--- T3 Combat Spawn (excluded): Divergences ---%n");
        printDivergenceTable(stats.t3UnitDivergence, 10);

        System.out.printf("%n--- Upgrades: Per-Type Accuracy ---%n");
        printUpgradeTable(stats);
        System.out.printf("%n--- Economy: MAPE by Field and Checkpoint ---%n");
        printEconomyTable(stats);
        System.out.printf("%n--- Per-Replay: Worst 10 ---%n");
        printWorstReplays(stats);
        System.out.printf("%nProcessed %d/%d replays%n", stats.processedReplays, stats.totalReplays);
    }

    private static double accPct(int java, int oracle) {
        return oracle > 0 ? 100.0 * Math.min(java, oracle) / oracle : 100.0;
    }

    private static void printDivergenceTable(Map<String, int[]> divergence, int limit) {
        System.out.printf("%-40s %6s %6s %6s%n", "Type", "Oracle", "Java", "Diff");
        divergence.entrySet().stream()
            .filter(e -> e.getValue()[0] != e.getValue()[1])
            .sorted((a, b) -> Integer.compare(
                Math.abs(b.getValue()[1] - b.getValue()[0]),
                Math.abs(a.getValue()[1] - a.getValue()[0])))
            .limit(limit)
            .forEach(e -> System.out.printf("%-40s %6d %6d %+6d%n",
                e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[1] - e.getValue()[0]));
    }

    private static void printUpgradeTable(BaselineStats stats) {
        Map<String, int[]> perType = new TreeMap<>();
        for (var entry : stats.upgradeDivergence.entrySet()) {
            String name = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            int[] counts = perType.computeIfAbsent(name, k -> new int[2]);
            counts[0] += entry.getValue()[0];
            counts[1] += entry.getValue()[1];
        }
        System.out.printf("%-45s %6s %6s %6s %8s%n", "UpgradeType", "Oracle", "Java", "Missed", "Accuracy");
        for (var entry : perType.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            int missed = oracle - Math.min(java, oracle);
            double accuracy = oracle > 0 ? 100.0 * Math.min(java, oracle) / oracle : 100.0;
            boolean cosmetic = COSMETIC_PREFIXES.stream().anyMatch(entry.getKey()::startsWith);
            String tag = cosmetic ? " [cosmetic]" : "";
            String marker = missed == 0 ? "✓" : "✗";
            System.out.printf("%s %-43s %6d %6d %6d %7.1f%%%s%n",
                marker, entry.getKey(), oracle, java, missed, accuracy, tag);
        }
    }

    private static void printEconomyTable(BaselineStats stats) {
        System.out.printf("%-45s", "Field");
        for (int min : CHECKPOINT_MINUTES) System.out.printf(" %6dmin", min);
        System.out.println();
        for (int fi = 0; fi < ECONOMY_FIELDS.length; fi++) {
            System.out.printf("%-45s", ECONOMY_FIELDS[fi]);
            for (int ci = 0; ci < CHECKPOINT_MINUTES.length; ci++) {
                List<Double> errors = stats.economyErrors[fi][ci];
                double mape = errors.isEmpty() ? 0 : errors.stream().mapToDouble(d -> d).average().orElse(0) * 100;
                System.out.printf(" %7.1f%%", mape);
            }
            System.out.println();
        }
    }

    private static void printWorstReplays(BaselineStats stats) {
        System.out.printf("%-60s %6s %6s %6s %8s%n", "Replay", "Units", "Bldgs", "Upgr", "Econ5m");
        stats.replaySummaries.stream()
            .sorted((a, b) -> {
                double scoreA = (100 - a.unitAccuracy()) + (100 - a.buildingAccuracy())
                                + (100 - a.upgradeAccuracy()) + a.economyMape5min();
                double scoreB = (100 - b.unitAccuracy()) + (100 - b.buildingAccuracy())
                                + (100 - b.upgradeAccuracy()) + b.economyMape5min();
                return Double.compare(scoreB, scoreA);
            })
            .limit(10)
            .forEach(r -> System.out.printf("%-60s %5.1f%% %5.1f%% %5.1f%% %6.1f%%%n",
                r.filename(), r.unitAccuracy(), r.buildingAccuracy(),
                r.upgradeAccuracy(), r.economyMape5min()));
    }

    private static double computeEconomyMape(BaselineStats stats, int targetMinute) {
        int ci = -1;
        for (int i = 0; i < CHECKPOINT_MINUTES.length; i++) {
            if (CHECKPOINT_MINUTES[i] == targetMinute) { ci = i; break; }
        }
        if (ci < 0) return 0;
        double sum = 0; int count = 0;
        for (int fi = 0; fi < ECONOMY_FIELDS.length; fi++) {
            for (double e : stats.economyErrors[fi][ci]) { sum += e; count++; }
        }
        return count > 0 ? (sum / count) * 100 : 0;
    }

    private void writeMarkdownReport(BaselineStats stats) throws IOException {
        Path reportPath = Path.of("../docs/benchmarks/oracle-accuracy-baseline.md");
        Files.createDirectories(reportPath.getParent());

        double unitAcc = accPct(stats.totalJavaUnits, stats.totalOracleUnits);
        double buildingAcc = accPct(stats.totalJavaBuildings, stats.totalOracleBuildings);
        double upgradeAcc = stats.gameplayOracleUpgrades > 0
            ? 100.0 * stats.gameplayMatchedUpgrades / stats.gameplayOracleUpgrades : 100.0;
        double economyMape5min = computeEconomyMape(stats, 5);
        double t1UnitAcc = accPct(stats.t1JavaUnits, stats.t1OracleUnits);
        double t1BuildingAcc = accPct(stats.t1JavaBuildings, stats.t1OracleBuildings);
        double t2UnitAcc = accPct(stats.t2JavaUnits, stats.t2OracleUnits);

        try (var pw = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            pw.printf("# Oracle Accuracy Baseline — %s%n%n", java.time.LocalDate.now());
            pw.printf("**Context:** Phase 2.5 baseline (#367/#373, child of #366)%n");
            pw.printf("**Dataset:** Oracle (118 replays, v4.9.3)%n");
            pw.printf("**Extractor:** `StrippedReplayFeatureExtractor`%n");
            pw.printf("**Processed:** %d / %d replays%n%n", stats.processedReplays, stats.totalReplays);
            pw.println("---");
            pw.println();

            pw.println("## Aggregate Summary");
            pw.println();
            pw.println("| Category | Accuracy | Notes |");
            pw.println("|----------|----------|-------|");
            pw.printf("| Units | %.1f%% | count match across %d replays |%n", unitAcc, stats.processedReplays);
            pw.printf("| Buildings | %.1f%% | count match, building types only |%n", buildingAcc);
            pw.printf("| Upgrades | %.1f%% | gameplay only (excl. cosmetics) |%n", upgradeAcc);
            pw.printf("| Economy | %.1f%% MAPE | mean across 13 fields at 5min |%n%n", economyMape5min);

            pw.println("## Tier Breakdown");
            pw.println();
            pw.println("| Tier | Category | Accuracy | Oracle | Java |");
            pw.println("|------|----------|----------|--------|------|");
            pw.printf("| T1 Commanded | Units | %.1f%% | %d | %d |%n", t1UnitAcc, stats.t1OracleUnits, stats.t1JavaUnits);
            pw.printf("| T1 Commanded | Buildings | %.1f%% | %d | %d |%n", t1BuildingAcc, stats.t1OracleBuildings, stats.t1JavaBuildings);
            pw.printf("| T2 Auto-spawn | Units | %.1f%% | %d | %d |%n", t2UnitAcc, stats.t2OracleUnits, stats.t2JavaUnits);
            pw.printf("| T3 Combat | Units | excluded | %d | %d |%n", stats.t3OracleUnits, stats.t3JavaUnits);
            pw.printf("| T4 Structural | Buildings | excluded | %d | %d |%n%n", stats.t4OracleBuildings, stats.t4JavaBuildings);

            pw.println("## T1 Commanded Units — Top Divergences");
            pw.println();
            writeDivergenceTable(pw, stats.t1UnitDivergence, 10);
            pw.println();

            pw.println("## T1 Commanded Buildings — Top Divergences");
            pw.println();
            writeDivergenceTable(pw, stats.t1BuildingDivergence, 10);
            pw.println();

            pw.println("## T2 Auto-spawn — Divergences");
            pw.println();
            writeDivergenceTable(pw, stats.t2UnitDivergence, 10);
            pw.println();

            pw.println("## Upgrades — Per-Type Accuracy");
            pw.println();
            writeUpgradeTable(pw, stats);
            pw.println();

            pw.println("## Economy — MAPE by Field and Checkpoint");
            pw.println();
            writeEconomyTable(pw, stats);
            pw.println();

            pw.println("## Per-Replay — Worst 10");
            pw.println();
            writeWorstReplays(pw, stats);
        }
        System.out.printf("%nReport written to %s%n", reportPath);
    }

    private static void writeDivergenceTable(PrintWriter pw, Map<String, int[]> divergence, int limit) {
        pw.println("| Type | Oracle | Java | Diff |");
        pw.println("|------|--------|------|------|");
        divergence.entrySet().stream()
            .filter(e -> e.getValue()[0] != e.getValue()[1])
            .sorted((a, b) -> Integer.compare(
                Math.abs(b.getValue()[1] - b.getValue()[0]),
                Math.abs(a.getValue()[1] - a.getValue()[0])))
            .limit(limit)
            .forEach(e -> pw.printf("| %s | %d | %d | %+d |%n",
                e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[1] - e.getValue()[0]));
    }

    private static void writeUpgradeTable(PrintWriter pw, BaselineStats stats) {
        pw.println("| UpgradeType | Oracle | Java | Missed | Accuracy |");
        pw.println("|-------------|--------|------|--------|----------|");
        Map<String, int[]> perType = new TreeMap<>();
        for (var entry : stats.upgradeDivergence.entrySet()) {
            String name = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            int[] counts = perType.computeIfAbsent(name, k -> new int[2]);
            counts[0] += entry.getValue()[0];
            counts[1] += entry.getValue()[1];
        }
        for (var entry : perType.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            int missed = oracle - Math.min(java, oracle);
            double accuracy = oracle > 0 ? 100.0 * Math.min(java, oracle) / oracle : 100.0;
            boolean cosmetic = COSMETIC_PREFIXES.stream().anyMatch(entry.getKey()::startsWith);
            String tag = cosmetic ? " [cosmetic]" : "";
            pw.printf("| %s%s | %d | %d | %d | %.1f%% |%n",
                entry.getKey(), tag, oracle, java, missed, accuracy);
        }
    }

    private static void writeEconomyTable(PrintWriter pw, BaselineStats stats) {
        pw.printf("| Field |");
        for (int min : CHECKPOINT_MINUTES) pw.printf(" %dmin |", min);
        pw.println();
        pw.printf("|-------|");
        for (int ignored : CHECKPOINT_MINUTES) pw.printf("------|");
        pw.println();
        for (int fi = 0; fi < ECONOMY_FIELDS.length; fi++) {
            pw.printf("| %s |", ECONOMY_FIELDS[fi]);
            for (int ci = 0; ci < CHECKPOINT_MINUTES.length; ci++) {
                List<Double> errors = stats.economyErrors[fi][ci];
                double mape = errors.isEmpty() ? 0
                    : errors.stream().mapToDouble(d -> d).average().orElse(0) * 100;
                pw.printf(" %.1f%% |", mape);
            }
            pw.println();
        }
    }

    private static void writeWorstReplays(PrintWriter pw, BaselineStats stats) {
        pw.println("| Replay | Units | Buildings | Upgrades | Economy 5min |");
        pw.println("|--------|-------|-----------|----------|--------------|");
        stats.replaySummaries.stream()
            .sorted((a, b) -> {
                double scoreA = (100 - a.unitAccuracy()) + (100 - a.buildingAccuracy())
                                + (100 - a.upgradeAccuracy()) + a.economyMape5min();
                double scoreB = (100 - b.unitAccuracy()) + (100 - b.buildingAccuracy())
                                + (100 - b.upgradeAccuracy()) + b.economyMape5min();
                return Double.compare(scoreB, scoreA);
            })
            .limit(10)
            .forEach(r -> pw.printf("| %s | %.1f%% | %.1f%% | %.1f%% | %.1f%% |%n",
                r.filename(), r.unitAccuracy(), r.buildingAccuracy(),
                r.upgradeAccuracy(), r.economyMape5min()));
    }

    private static class BaselineStats {
        int totalReplays;
        int processedReplays;

        Map<String, int[]> unitBornDivergence = new TreeMap<>();
        int totalOracleUnits, totalJavaUnits;
        Map<String, int[]> buildingDivergence = new TreeMap<>();
        int totalOracleBuildings, totalJavaBuildings;

        Map<String, int[]> t1UnitDivergence = new TreeMap<>();
        int t1OracleUnits, t1JavaUnits;
        Map<String, int[]> t1BuildingDivergence = new TreeMap<>();
        int t1OracleBuildings, t1JavaBuildings;

        Map<String, int[]> t2UnitDivergence = new TreeMap<>();
        int t2OracleUnits, t2JavaUnits;

        Map<String, int[]> t3UnitDivergence = new TreeMap<>();
        int t3OracleUnits, t3JavaUnits;

        Map<String, int[]> t4BuildingDivergence = new TreeMap<>();
        int t4OracleBuildings, t4JavaBuildings;

        Map<String, int[]> upgradeDivergence = new TreeMap<>();
        int totalOracleUpgrades, totalJavaUpgrades;
        int gameplayOracleUpgrades, gameplayMatchedUpgrades;

        List<Double>[][] economyErrors;
        int[] economyReplayCount;
        List<ReplaySummary> replaySummaries = new ArrayList<>();

        @SuppressWarnings("unchecked")
        BaselineStats() {
            economyErrors = new List[ECONOMY_FIELDS.length][CHECKPOINT_MINUTES.length];
            for (int f = 0; f < ECONOMY_FIELDS.length; f++) {
                for (int c = 0; c < CHECKPOINT_MINUTES.length; c++) {
                    economyErrors[f][c] = new ArrayList<>();
                }
            }
            economyReplayCount = new int[CHECKPOINT_MINUTES.length];
        }
    }

    private record ReplaySummary(String filename, double unitAccuracy,
                                  double buildingAccuracy, double upgradeAccuracy,
                                  double economyMape5min) {}
}
