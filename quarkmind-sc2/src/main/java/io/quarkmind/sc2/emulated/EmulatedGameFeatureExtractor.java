package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;

import java.util.*;

public class EmulatedGameFeatureExtractor {

    private static final int PLAYER_STATS_INTERVAL = 160;

    private static final Map<String, String> UNIT_NAME_OVERRIDES = Map.ofEntries(
            Map.entry("SCV", "SCV"),
            Map.entry("MULE", "MULE"),
            Map.entry("HELLBAT", "HellionTank"),
            Map.entry("VIKING", "VikingFighter"),
            Map.entry("SIEGE_TANK_SIEGED", "SiegeTank"),
            Map.entry("WARP_PRISM_PHASING", "WarpPrism"),
            Map.entry("ADEPT_PHASE_SHIFT", "Adept"),
            Map.entry("LIBERATOR_AG", "Liberator"),
            Map.entry("VIKING_ASSAULT", "VikingAssault")
    );

    private final List<Map<String, Object>> events = new ArrayList<>();
    private Map<UnitType, Integer> prevPlayerUnits = new EnumMap<>(UnitType.class);
    private Map<UnitType, Integer> prevEnemyUnits = new EnumMap<>(UnitType.class);
    private Map<BuildingType, Integer> prevPlayerBuildings = new EnumMap<>(BuildingType.class);
    private Map<BuildingType, Integer> prevEnemyBuildings = new EnumMap<>(BuildingType.class);
    private Set<String> prevPlayerUpgrades = Set.of();
    private Set<String> prevEnemyUpgrades = Set.of();
    private int lastStatsLoop = -PLAYER_STATS_INTERVAL;
    private long lastLoop = 0;
    private boolean firstSnapshot = true;

    public void addSnapshot(GameState state, int gameLoop) {
        if (firstSnapshot) {
            prevPlayerUnits = countUnits(state.myUnits());
            prevEnemyUnits = countUnits(state.enemyUnits());
            prevPlayerBuildings = countBuildings(state.myBuildings());
            prevEnemyBuildings = countBuildings(state.enemyBuildings());
            prevPlayerUpgrades = new HashSet<>(state.playerUpgrades());
            prevEnemyUpgrades = new HashSet<>(state.enemyUpgrades());
            firstSnapshot = false;
            lastLoop = gameLoop;
            emitPlayerStats(state, gameLoop, 1);
            emitPlayerStats(state, gameLoop, 2);
            lastStatsLoop = gameLoop;
            emitInitialUnits(state.myUnits(), gameLoop, 1);
            emitInitialUnits(state.enemyUnits(), gameLoop, 2);
            emitInitialBuildings(state.myBuildings(), gameLoop, 1);
            emitInitialBuildings(state.enemyBuildings(), gameLoop, 2);
            return;
        }

        diffUnits(state.myUnits(), prevPlayerUnits, gameLoop, 1);
        diffUnits(state.enemyUnits(), prevEnemyUnits, gameLoop, 2);
        diffBuildings(state.myBuildings(), prevPlayerBuildings, gameLoop, 1);
        diffBuildings(state.enemyBuildings(), prevEnemyBuildings, gameLoop, 2);
        diffUpgrades(state.playerUpgrades(), prevPlayerUpgrades, gameLoop, 1);
        diffUpgrades(state.enemyUpgrades(), prevEnemyUpgrades, gameLoop, 2);

        if (gameLoop - lastStatsLoop >= PLAYER_STATS_INTERVAL) {
            emitPlayerStats(state, gameLoop, 1);
            emitPlayerStats(state, gameLoop, 2);
            lastStatsLoop = gameLoop;
        }

        prevPlayerUnits = countUnits(state.myUnits());
        prevEnemyUnits = countUnits(state.enemyUnits());
        prevPlayerBuildings = countBuildings(state.myBuildings());
        prevEnemyBuildings = countBuildings(state.enemyBuildings());
        prevPlayerUpgrades = new HashSet<>(state.playerUpgrades());
        prevEnemyUpgrades = new HashSet<>(state.enemyUpgrades());
        lastLoop = gameLoop;
    }

    public Map<String, Object> finalise(Race playerRace, Race opponentRace) {
        events.sort(Comparator.comparingLong(e -> (long) e.get("loop")));

        var result = new LinkedHashMap<String, Object>();
        result.put("ToonPlayerDescMap", buildPlayerDescMap(playerRace, opponentRace));
        result.put("trackerEvents", new ArrayList<>(events));
        result.put("header", Map.of("elapsedGameLoops", lastLoop, "baseBuild", 0));
        result.put("metadata", Map.of("extractionMethod", "emulator", "emulatorVersion", "1.0"));
        return result;
    }

    public Map<String, Object> extract(List<GameState> snapshots, int loopsPerSnapshot,
                                       Race playerRace, Race opponentRace) {
        for (int i = 0; i < snapshots.size(); i++) {
            addSnapshot(snapshots.get(i), i * loopsPerSnapshot);
        }
        return finalise(playerRace, opponentRace);
    }

    private void emitInitialUnits(List<Unit> units, int loop, int playerId) {
        var counts = countUnits(units);
        for (var entry : counts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                events.add(unitEvent("UnitBorn", loop, playerId,
                        toSc2Name(entry.getKey().name())));
            }
        }
    }

    private void emitInitialBuildings(List<Building> buildings, int loop, int playerId) {
        var counts = countBuildings(buildings);
        for (var entry : counts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                String name = toSc2Name(entry.getKey().name());
                events.add(unitEvent("UnitInit", loop, playerId, name));
                events.add(unitEvent("UnitDone", loop, playerId, name));
            }
        }
    }

    private void diffUnits(List<Unit> current, Map<UnitType, Integer> prev,
                           int loop, int playerId) {
        var currentCounts = countUnits(current);
        for (var type : UnitType.values()) {
            int now = currentCounts.getOrDefault(type, 0);
            int was = prev.getOrDefault(type, 0);
            int delta = now - was;
            String name = toSc2Name(type.name());
            if (delta > 0) {
                for (int i = 0; i < delta; i++) {
                    events.add(unitEvent("UnitBorn", loop, playerId, name));
                }
            } else if (delta < 0) {
                for (int i = 0; i < -delta; i++) {
                    events.add(unitEvent("UnitDied", loop, playerId, name));
                }
            }
        }
    }

    private void diffBuildings(List<Building> current, Map<BuildingType, Integer> prev,
                               int loop, int playerId) {
        var currentCounts = countBuildings(current);
        for (var type : BuildingType.values()) {
            int now = currentCounts.getOrDefault(type, 0);
            int was = prev.getOrDefault(type, 0);
            int delta = now - was;
            String name = toSc2Name(type.name());
            if (delta > 0) {
                for (int i = 0; i < delta; i++) {
                    events.add(unitEvent("UnitInit", loop, playerId, name));
                    events.add(unitEvent("UnitDone", loop, playerId, name));
                }
            } else if (delta < 0) {
                for (int i = 0; i < -delta; i++) {
                    events.add(unitEvent("UnitDied", loop, playerId, name));
                }
            }
        }
    }

    private void diffUpgrades(Set<String> current, Set<String> prev,
                              int loop, int playerId) {
        for (String upgrade : current) {
            if (!prev.contains(upgrade)) {
                events.add(Map.of(
                        "evtTypeName", "Upgrade",
                        "loop", (long) loop,
                        "playerId", playerId,
                        "upgradeTypeName", upgrade));
            }
        }
    }

    private void emitPlayerStats(GameState state, int loop, int playerId) {
        PlayerEconomyStats eco = playerId == 1 ? state.playerEconomy() : state.enemyEconomy();
        var stats = new LinkedHashMap<String, Object>();
        stats.put("scoreValueMineralsCurrent", (long) eco.mineralsCurrent() * 1000);
        stats.put("scoreValueVespeneCurrent", (long) eco.vespeneCurrent() * 1000);
        stats.put("scoreValueMineralsCollectionRate", (long) eco.mineralsCollectionRate() * 1000);
        stats.put("scoreValueVespeneCollectionRate", (long) eco.vespeneCollectionRate() * 1000);
        stats.put("scoreValueFoodMade", (long) eco.foodMade());
        stats.put("scoreValueFoodUsed", (long) eco.foodUsed());
        stats.put("scoreValueWorkersActiveCount", (long) eco.workersActiveCount() * 1000);
        stats.put("scoreValueMineralsUsedCurrentArmy", (long) eco.mineralsUsedCurrentArmy() * 1000);
        stats.put("scoreValueMineralsUsedCurrentEconomy", (long) eco.mineralsUsedCurrentEconomy() * 1000);
        stats.put("scoreValueMineralsUsedCurrentTechnology", (long) eco.mineralsUsedCurrentTechnology() * 1000);
        stats.put("scoreValueVespeneUsedCurrentArmy", (long) eco.vespeneUsedCurrentArmy() * 1000);
        stats.put("scoreValueVespeneUsedCurrentEconomy", (long) eco.vespeneUsedCurrentEconomy() * 1000);
        stats.put("scoreValueVespeneUsedCurrentTechnology", (long) eco.vespeneUsedCurrentTechnology() * 1000);

        events.add(Map.of(
                "evtTypeName", "PlayerStats",
                "loop", (long) loop,
                "controlPlayerId", playerId,
                "stats", stats));
    }

    static String toSc2Name(String enumName) {
        String override = UNIT_NAME_OVERRIDES.get(enumName);
        if (override != null) return override;

        var parts = enumName.split("_");
        var sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static Map<String, Object> unitEvent(String type, int loop,
                                                  int playerId, String unitTypeName) {
        return Map.of(
                "evtTypeName", type,
                "loop", (long) loop,
                "controlPlayerId", playerId,
                "unitTypeName", unitTypeName,
                "unitTagIndex", 0,
                "unitTagRecycle", 0);
    }

    private static Map<UnitType, Integer> countUnits(List<Unit> units) {
        var counts = new EnumMap<UnitType, Integer>(UnitType.class);
        for (var u : units) counts.merge(u.type(), 1, Integer::sum);
        return counts;
    }

    private static Map<BuildingType, Integer> countBuildings(List<Building> buildings) {
        var counts = new EnumMap<BuildingType, Integer>(BuildingType.class);
        for (var b : buildings) counts.merge(b.type(), 1, Integer::sum);
        return counts;
    }

    private static Map<String, Object> buildPlayerDescMap(Race playerRace, Race opponentRace) {
        return Map.of(
                "1", Map.of("playerID", 1, "race", raceName(playerRace)),
                "2", Map.of("playerID", 2, "race", raceName(opponentRace)));
    }

    private static String raceName(Race race) {
        return switch (race) {
            case PROTOSS -> "Prot";
            case TERRAN -> "Terr";
            case ZERG -> "Zerg";
        };
    }
}
