package io.quarkmind.sc2.replay;

import io.quarkmind.domain.ScoutingObservation;

import java.util.*;

public class ScoutingObservationExtractor {

    private static final Set<String> SUPPLY_BUILDINGS = Set.of(
        "SupplyDepot", "SupplyDepotLowered", "Pylon",
        "Overlord", "OverlordCocoon", "Overseer"
    );

    private static final Set<String> BASE_BUILDINGS = Set.of(
        "Nexus", "CommandCenter", "OrbitalCommand", "PlanetaryFortress",
        "Hatchery", "Lair", "Hive"
    );

    private static final Set<String> GAS_BUILDINGS = Set.of(
        "Assimilator", "Extractor", "Refinery"
    );

    private static final Set<String> TERRAN_TECH = Set.of(
        "Factory", "Starport", "GhostAcademy", "FusionCore", "Armory"
    );
    private static final Set<String> ZERG_TECH = Set.of(
        "SpawningPool", "BanelingNest", "RoachWarren", "HydraliskDen",
        "Spire", "InfestationPit", "LurkerDen", "UltraliskCavern"
    );
    private static final Set<String> PROTOSS_TECH = Set.of(
        "TwilightCouncil", "Stargate", "RoboticsFacility", "DarkShrine",
        "TemplarArchives", "RoboticsBay", "FleetBeacon"
    );

    private static final Set<String> WORKER_UNITS = Set.of("SCV", "Probe", "Drone");
    private static final Set<String> LIGHT_UNITS = Set.of(
        "Marine", "Zergling", "Zealot", "Adept", "Hellion"
    );
    private static final Set<String> ARMORED_UNITS = Set.of(
        "Roach", "SiegeTank", "SiegeTankSieged", "Stalker", "Immortal", "Marauder"
    );
    private static final Set<String> AIR_UNITS = Set.of(
        "Mutalisk", "Banshee", "Phoenix", "VoidRay", "Oracle",
        "Liberator", "LiberatorAG", "Viking", "VikingFighter"
    );

    static final long GAS_EARLY = 2016;
    static final long GAS_LATE = 3360;
    static final long EXPAND_FAST = 2688;
    static final long EXPAND_LATE = 4704;
    static final long AGGRESSION_EARLY = 4032;
    static final long AGGRESSION_LATE = 6720;
    static final long SNAPSHOT_LOOP = 6720;

    @SuppressWarnings("unchecked")
    public static List<ScoutingObservation> extract(Map<String, Object> gameJson, int playerId) {
        var events = (List<Map<String, Object>>) gameJson.get("trackerEvents");
        var observations = new ArrayList<ScoutingObservation>();

        String firstBuilding = null;
        long firstBuildingLoop = -1;
        long firstGasLoop = -1;
        int baseCount = 1;
        long secondBaseLoop = -1;
        String firstTech = null;
        long firstTechLoop = -1;
        long firstMilitaryLoop = -1;

        int lightCount = 0, armoredCount = 0, airCount = 0;
        int workerCount = 0;

        Set<String> techBuildings = techBuildingsForPlayer(gameJson, playerId);

        for (var event : events) {
            int pid = ((Number) event.get("controlPlayerId")).intValue();
            if (pid != playerId) continue;

            String type = (String) event.get("evtTypeName");
            String unitType = (String) event.getOrDefault("unitTypeName", "");
            long loop = ((Number) event.get("loop")).longValue();

            if ("UnitInit".equals(type) || "UnitBorn".equals(type)) {
                if ("UnitInit".equals(type) && firstBuilding == null
                        && !SUPPLY_BUILDINGS.contains(unitType)
                        && !BASE_BUILDINGS.contains(unitType)
                        && !GAS_BUILDINGS.contains(unitType)) {
                    firstBuilding = unitType.toLowerCase();
                    firstBuildingLoop = loop;
                }

                if (firstGasLoop < 0 && GAS_BUILDINGS.contains(unitType)) {
                    firstGasLoop = loop;
                }

                if ("UnitInit".equals(type) && BASE_BUILDINGS.contains(unitType)) {
                    baseCount++;
                    if (secondBaseLoop < 0 && baseCount >= 2) {
                        secondBaseLoop = loop;
                    }
                }

                if (firstTech == null && techBuildings.contains(unitType)) {
                    firstTech = unitType.toLowerCase();
                    firstTechLoop = loop;
                }

                if (firstMilitaryLoop < 0
                        && !WORKER_UNITS.contains(unitType)
                        && !SUPPLY_BUILDINGS.contains(unitType)
                        && !BASE_BUILDINGS.contains(unitType)
                        && !GAS_BUILDINGS.contains(unitType)
                        && !"Larva".equals(unitType)
                        && (LIGHT_UNITS.contains(unitType) || ARMORED_UNITS.contains(unitType)
                            || AIR_UNITS.contains(unitType))) {
                    firstMilitaryLoop = loop;
                }

                if (loop <= SNAPSHOT_LOOP) {
                    if (WORKER_UNITS.contains(unitType)) workerCount++;
                    if (LIGHT_UNITS.contains(unitType)) lightCount++;
                    if (ARMORED_UNITS.contains(unitType)) armoredCount++;
                    if (AIR_UNITS.contains(unitType)) airCount++;
                }
            }
        }

        if (firstBuilding != null) {
            observations.add(new ScoutingObservation("first_building", firstBuilding, firstBuildingLoop));
        }

        String gasTiming;
        if (firstGasLoop < 0 || firstGasLoop > SNAPSHOT_LOOP) gasTiming = "none";
        else if (firstGasLoop < GAS_EARLY) gasTiming = "early";
        else if (firstGasLoop <= GAS_LATE) gasTiming = "normal";
        else gasTiming = "late";
        long gasObsLoop = firstGasLoop > 0 ? firstGasLoop : SNAPSHOT_LOOP;
        observations.add(new ScoutingObservation("gas_timing", gasTiming, gasObsLoop));

        String expansionTiming;
        if (secondBaseLoop < 0 || secondBaseLoop > SNAPSHOT_LOOP) expansionTiming = "none";
        else if (secondBaseLoop < EXPAND_FAST) expansionTiming = "fast";
        else if (secondBaseLoop <= EXPAND_LATE) expansionTiming = "normal";
        else expansionTiming = "late";
        long expandObsLoop = secondBaseLoop > 0 ? secondBaseLoop : SNAPSHOT_LOOP;
        observations.add(new ScoutingObservation("expansion_timing", expansionTiming, expandObsLoop));

        if (firstTech != null) {
            observations.add(new ScoutingObservation("tech_path", firstTech, firstTechLoop));
        }

        int totalMilitary = lightCount + armoredCount + airCount;
        String armyComp;
        if (totalMilitary == 0) armyComp = "none";
        else if ((double) airCount / totalMilitary > 0.5) armyComp = "air";
        else if ((double) lightCount / totalMilitary > 0.6) armyComp = "light";
        else if ((double) armoredCount / totalMilitary > 0.6) armyComp = "armored";
        else armyComp = "mixed";
        observations.add(new ScoutingObservation("army_composition", armyComp, SNAPSHOT_LOOP));

        int effectiveBases = Math.max(1, baseCount);
        double workersPerBase = (double) workerCount / effectiveBases;
        String saturation;
        if (workersPerBase < 16) saturation = "under";
        else if (workersPerBase <= 22) saturation = "saturated";
        else saturation = "over";
        observations.add(new ScoutingObservation("worker_saturation", saturation, SNAPSHOT_LOOP));

        String aggression;
        if (firstMilitaryLoop < 0) aggression = "no_aggression";
        else if (firstMilitaryLoop < AGGRESSION_EARLY) aggression = "early_pressure";
        else if (firstMilitaryLoop <= AGGRESSION_LATE) aggression = "timing_attack";
        else aggression = "macro_play";
        long aggrObsLoop = firstMilitaryLoop > 0 ? firstMilitaryLoop : SNAPSHOT_LOOP;
        observations.add(new ScoutingObservation("aggression_timing", aggression, aggrObsLoop));

        observations.sort(Comparator.comparingLong(ScoutingObservation::detectedAtLoop));
        return List.copyOf(observations);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> techBuildingsForPlayer(Map<String, Object> gameJson, int playerId) {
        var playerMap = (Map<String, Map<String, Object>>) gameJson.get("ToonPlayerDescMap");
        var playerData = playerMap.get(String.valueOf(playerId));
        String race = (String) playerData.get("race");
        return switch (race) {
            case "Terran" -> TERRAN_TECH;
            case "Zerg" -> ZERG_TECH;
            case "Protoss" -> PROTOSS_TECH;
            default -> Set.of();
        };
    }
}
