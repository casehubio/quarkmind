package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class FeatureIndexMaps {

    static final int N_BUILDINGS = 53;
    static final int N_UNITS = 53;
    static final int N_STATS = 13;
    static final int N_UPGRADES = 15;
    static final int N_FEATURES_PER_PLAYER = N_BUILDINGS + N_UNITS + N_STATS + N_UPGRADES;

    static final Map<BuildingType, Integer> BUILDING_INDEX = buildBuildingIndex();
    static final Map<UnitType, Integer> UNIT_INDEX = buildUnitIndex();

    static final List<String> UPGRADE_NAMES = List.of(
        "Stimpack",
        "ShieldWall",
        "PunisherGrenades",
        "BansheeCloak",
        "TerranVehicleWeaponsLevel1",
        "PersonalCloaking",
        "DrillClaws",
        "zerglingmovementspeed",
        "GlialReconstitution",
        "CentrificalHooks",
        "Burrow",
        "WarpGateResearch",
        "BlinkTech",
        "Charge",
        "AdeptPiercingAttack"
    );

    private static Map<BuildingType, Integer> buildBuildingIndex() {
        // Ordering matches sc2egset_extractor.py BUILDINGS list exactly (53 entries)
        // Java BuildingType enum values mapped to their neocortex feature index.
        // Neocortex buildings not in the Java enum (BarracksReactor, BarracksTechLab,
        // FactoryReactor, FactoryTechLab, StarportReactor, StarportTechLab, WarpGate)
        // have no mapping — they produce empty feature slots.
        var map = new EnumMap<BuildingType, Integer>(BuildingType.class);
        map.put(BuildingType.COMMAND_CENTER, 0);
        map.put(BuildingType.ORBITAL_COMMAND, 1);
        map.put(BuildingType.PLANETARY_FORTRESS, 2);
        map.put(BuildingType.BARRACKS, 3);
        map.put(BuildingType.FACTORY, 4);
        map.put(BuildingType.STARPORT, 5);
        map.put(BuildingType.ENGINEERING_BAY, 6);
        map.put(BuildingType.ARMORY, 7);
        map.put(BuildingType.GHOST_ACADEMY, 8);
        map.put(BuildingType.FUSION_CORE, 9);
        map.put(BuildingType.BUNKER, 10);
        map.put(BuildingType.MISSILE_TURRET, 11);
        map.put(BuildingType.SENSOR_TOWER, 12);
        map.put(BuildingType.SUPPLY_DEPOT, 13);
        map.put(BuildingType.REFINERY, 14);
        // 15: BarracksReactor — no Java enum
        // 16: BarracksTechLab — no Java enum
        // 17: FactoryReactor — no Java enum
        // 18: FactoryTechLab — no Java enum
        // 19: StarportReactor — no Java enum
        // 20: StarportTechLab — no Java enum
        map.put(BuildingType.HATCHERY, 21);
        map.put(BuildingType.LAIR, 22);
        map.put(BuildingType.HIVE, 23);
        map.put(BuildingType.SPAWNING_POOL, 24);
        map.put(BuildingType.BANELING_NEST, 25);
        map.put(BuildingType.ROACH_WARREN, 26);
        map.put(BuildingType.EVOLUTION_CHAMBER, 27);
        map.put(BuildingType.EXTRACTOR, 28);
        map.put(BuildingType.HYDRALISK_DEN, 29);
        map.put(BuildingType.SPINE_CRAWLER, 30);
        map.put(BuildingType.SPORE_CRAWLER, 31);
        map.put(BuildingType.SPIRE, 32);
        map.put(BuildingType.GREATER_SPIRE, 33);
        map.put(BuildingType.INFESTATION_PIT, 34);
        map.put(BuildingType.NYDUS_NETWORK, 35);
        map.put(BuildingType.ULTRALISK_CAVERN, 36);
        map.put(BuildingType.NEXUS, 37);
        map.put(BuildingType.GATEWAY, 38);
        // 39: WarpGate — no Java enum (Gateway covers both)
        map.put(BuildingType.CYBERNETICS_CORE, 40);
        map.put(BuildingType.FORGE, 41);
        map.put(BuildingType.ASSIMILATOR, 42);
        map.put(BuildingType.PYLON, 43);
        map.put(BuildingType.PHOTON_CANNON, 44);
        map.put(BuildingType.SHIELD_BATTERY, 45);
        map.put(BuildingType.ROBOTICS_FACILITY, 46);
        map.put(BuildingType.ROBOTICS_BAY, 47);
        map.put(BuildingType.STARGATE, 48);
        map.put(BuildingType.FLEET_BEACON, 49);
        map.put(BuildingType.TWILIGHT_COUNCIL, 50);
        map.put(BuildingType.TEMPLAR_ARCHIVES, 51);
        map.put(BuildingType.DARK_SHRINE, 52);
        return Map.copyOf(map);
    }

    private static Map<UnitType, Integer> buildUnitIndex() {
        // Ordering matches sc2egset_extractor.py UNITS list exactly (53 entries)
        // Offset by N_BUILDINGS (53) within the per-player feature vector.
        var map = new EnumMap<UnitType, Integer>(UnitType.class);
        map.put(UnitType.SCV, 0);
        map.put(UnitType.MARINE, 1);
        map.put(UnitType.MARAUDER, 2);
        map.put(UnitType.REAPER, 3);
        map.put(UnitType.GHOST, 4);
        map.put(UnitType.HELLION, 5);
        map.put(UnitType.HELLBAT, 6);       // neocortex: HellionTank
        map.put(UnitType.SIEGE_TANK, 7);
        map.put(UnitType.CYCLONE, 8);
        map.put(UnitType.THOR, 9);
        map.put(UnitType.MEDIVAC, 10);
        map.put(UnitType.VIKING, 11);       // neocortex: VikingFighter
        map.put(UnitType.VIKING_ASSAULT, 12);
        map.put(UnitType.LIBERATOR, 13);
        map.put(UnitType.BANSHEE, 14);
        map.put(UnitType.RAVEN, 15);
        map.put(UnitType.WIDOW_MINE, 16);
        map.put(UnitType.DRONE, 17);
        map.put(UnitType.ZERGLING, 18);
        map.put(UnitType.BANELING, 19);
        map.put(UnitType.ROACH, 20);
        map.put(UnitType.RAVAGER, 21);
        map.put(UnitType.QUEEN, 22);
        map.put(UnitType.MUTALISK, 23);
        map.put(UnitType.CORRUPTOR, 24);
        map.put(UnitType.BROOD_LORD, 25);
        map.put(UnitType.HYDRALISK, 26);
        map.put(UnitType.LURKER, 27);
        map.put(UnitType.INFESTOR, 28);
        map.put(UnitType.SWARM_HOST, 29);
        map.put(UnitType.ULTRALISK, 30);
        map.put(UnitType.VIPER, 31);
        map.put(UnitType.OVERLORD, 32);
        map.put(UnitType.OVERSEER, 33);
        map.put(UnitType.PROBE, 34);
        map.put(UnitType.ZEALOT, 35);
        map.put(UnitType.STALKER, 36);
        map.put(UnitType.SENTRY, 37);
        map.put(UnitType.ADEPT, 38);
        map.put(UnitType.HIGH_TEMPLAR, 39);
        map.put(UnitType.DARK_TEMPLAR, 40);
        map.put(UnitType.ARCHON, 41);
        map.put(UnitType.IMMORTAL, 42);
        map.put(UnitType.COLOSSUS, 43);
        map.put(UnitType.DISRUPTOR, 44);
        map.put(UnitType.WARP_PRISM, 45);
        map.put(UnitType.PHOENIX, 46);
        map.put(UnitType.ORACLE, 47);
        map.put(UnitType.VOID_RAY, 48);
        map.put(UnitType.CARRIER, 49);
        map.put(UnitType.TEMPEST, 50);
        map.put(UnitType.MOTHERSHIP, 51);
        map.put(UnitType.OBSERVER, 52);
        return Map.copyOf(map);
    }

    private FeatureIndexMaps() {}
}
