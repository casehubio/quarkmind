package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.observation.PlayerCommon;
import com.github.ocraft.s2client.protocol.observation.raw.ObservationRaw;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import io.quarkmind.domain.*;

import java.util.List;
import java.util.Set;

/**
 * Pure function — translates an ocraft-protocol {@link Observation} snapshot into our
 * {@link GameState}. No CDI, no framework dependencies. Unit-testable without SC2.
 *
 * <p>Naming note: both {@code io.quarkmind.domain.Unit} and
 * {@code com.github.ocraft.s2client.protocol.unit.Unit} are in scope. The protocol
 * type is always fully-qualified to avoid ambiguity.
 */
public final class ObservationTranslator {

    // All building types across all races — used to distinguish units from structures.
    private static final Set<Units> ALL_BUILDINGS = Set.of(
        // Protoss
        Units.PROTOSS_NEXUS, Units.PROTOSS_PYLON,
        Units.PROTOSS_GATEWAY, Units.PROTOSS_WARP_GATE,
        Units.PROTOSS_CYBERNETICS_CORE, Units.PROTOSS_ASSIMILATOR,
        Units.PROTOSS_ROBOTICS_FACILITY, Units.PROTOSS_STARGATE,
        Units.PROTOSS_FORGE, Units.PROTOSS_TWILIGHT_COUNCIL,
        Units.PROTOSS_PHOTON_CANNON, Units.PROTOSS_SHIELD_BATTERY,
        Units.PROTOSS_DARK_SHRINE, Units.PROTOSS_TEMPLAR_ARCHIVE,
        Units.PROTOSS_FLEET_BEACON, Units.PROTOSS_ROBOTICS_BAY,
        // Terran
        Units.TERRAN_COMMAND_CENTER, Units.TERRAN_ORBITAL_COMMAND,
        Units.TERRAN_ORBITAL_COMMAND_FLYING, Units.TERRAN_PLANETARY_FORTRESS,
        Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT_LOWERED,
        Units.TERRAN_BARRACKS, Units.TERRAN_BARRACKS_FLYING,
        Units.TERRAN_BARRACKS_TECHLAB, Units.TERRAN_BARRACKS_REACTOR,
        Units.TERRAN_ENGINEERING_BAY, Units.TERRAN_ARMORY,
        Units.TERRAN_MISSILE_TURRET, Units.TERRAN_BUNKER,
        Units.TERRAN_SENSOR_TOWER, Units.TERRAN_GHOST_ACADEMY,
        Units.TERRAN_FACTORY, Units.TERRAN_FACTORY_FLYING,
        Units.TERRAN_FACTORY_TECHLAB, Units.TERRAN_FACTORY_REACTOR,
        Units.TERRAN_STARPORT, Units.TERRAN_STARPORT_FLYING,
        Units.TERRAN_STARPORT_TECHLAB, Units.TERRAN_STARPORT_REACTOR,
        Units.TERRAN_FUSION_CORE, Units.TERRAN_REFINERY,
        // Zerg
        Units.ZERG_HATCHERY, Units.ZERG_LAIR, Units.ZERG_HIVE,
        Units.ZERG_SPAWNING_POOL, Units.ZERG_EVOLUTION_CHAMBER,
        Units.ZERG_ROACH_WARREN, Units.ZERG_BANELING_NEST,
        Units.ZERG_SPINE_CRAWLER, Units.ZERG_SPINE_CRAWLER_UPROOTED,
        Units.ZERG_SPORE_CRAWLER, Units.ZERG_SPORE_CRAWLER_UPROOTED,
        Units.ZERG_HYDRALISK_DEN, Units.ZERG_LURKER_DEN_MP,
        Units.ZERG_INFESTATION_PIT, Units.ZERG_SPIRE, Units.ZERG_GREATER_SPIRE,
        Units.ZERG_NYDUS_NETWORK, Units.ZERG_NYDUS_CANAL,
        Units.ZERG_ULTRALISK_CAVERN, Units.ZERG_EXTRACTOR
    );
    private static final Set<Units> ALL_GEYSERS   = Set.of(
            Units.NEUTRAL_VESPENE_GEYSER,
            Units.NEUTRAL_RICH_VESPENE_GEYSER,
            Units.NEUTRAL_PROTOSS_VESPENE_GEYSER,
            Units.NEUTRAL_PURIFIER_VESPENE_GEYSER,
            Units.NEUTRAL_SHAKURAS_VESPENE_GEYSER,
            Units.NEUTRAL_SPACE_PLATFORM_GEYSER
                                                          );

    private static final Set<Units> ALL_MINERAL_PATCHES = Set.of(
            Units.NEUTRAL_MINERAL_FIELD,
            Units.NEUTRAL_MINERAL_FIELD750,
            Units.NEUTRAL_MINERAL_FIELD450,
            Units.NEUTRAL_RICH_MINERAL_FIELD,
            Units.NEUTRAL_RICH_MINERAL_FIELD750,
            Units.NEUTRAL_LAB_MINERAL_FIELD,
            Units.NEUTRAL_LAB_MINERAL_FIELD750,
            Units.NEUTRAL_PURIFIER_MINERAL_FIELD,
            Units.NEUTRAL_PURIFIER_MINERAL_FIELD750,
            Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD,
            Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD750
                                                                );


    private ObservationTranslator() {}

    /**
     * Translates an ocraft-protocol {@link Observation} to a {@link GameState}.
     *
     * <p>{@code obs.getRaw().orElseThrow()} is safe because {@code InterfaceOptions.raw=true}
     * in {@code RequestJoinGame} guarantees ObservationRaw is always populated.
     *
     * <p>ObservationRaw.getUnits() returns {@code Set<Unit>} built with
     * {@code .filter(Raw.Unit::hasTag).map(Unit::from)} — every element is non-null.
     * The {@code u.unit() != null} guard from the old {@code UnitInPool} path is absent.
     *
     * <p>Observation.getGameLoop() returns {@code int}; GameState.gameFrame is {@code long} —
     * widening from int to long is implicit in the constructor call.
     */
    public static GameState translate(Observation obs, io.quarkmind.domain.MapInfo mapInfo) {
        ObservationRaw                                     raw      = obs.getRaw().orElseThrow();
        Set<com.github.ocraft.s2client.protocol.unit.Unit> allUnits = raw.getUnits();

        var selfUnits = allUnits.stream()
                                .filter(u -> u.getAlliance() == Alliance.SELF)
                                .toList();
        var enemyUnits = allUnits.stream()
                                 .filter(u -> u.getAlliance() == Alliance.ENEMY)
                                 .toList();
        var neutralUnits = allUnits.stream()
                                   .filter(u -> u.getAlliance() == Alliance.NEUTRAL)
                                   .toList();

        List<Unit> myUnits = selfUnits.stream()
                                      .filter(u -> !isBuilding(toUnitsEnum(u)))
                                      .map(ObservationTranslator::toUnit)
                                      .toList();

        List<Building> myBuildings = selfUnits.stream()
                                              .filter(u -> isBuilding(toUnitsEnum(u)))
                                              .map(ObservationTranslator::toBuilding)
                                              .toList();

        List<Unit> enemies = enemyUnits.stream()
                                       .map(ObservationTranslator::toUnit)
                                       .toList();

        List<Resource> geysers = neutralUnits.stream()
                                             .filter(u -> isGeyser(toUnitsEnum(u)))
                                             .map(u -> toResource(u, defaultGeyserAmount(toUnitsEnum(u))))
                                             .toList();

        List<Resource> mineralPatches = neutralUnits.stream()
                                                    .filter(u -> isMineralPatch(toUnitsEnum(u)))
                                                    .map(u -> toResource(u, defaultMineralAmount(toUnitsEnum(u))))
                                                    .toList();

        PlayerCommon common = obs.getPlayerCommon();
        return new GameState(
                common.getMinerals(),
                common.getVespene(),
                common.getFoodCap(),
                common.getFoodUsed(),
                myUnits,
                myBuildings,
                enemies,
                List.of(),   // enemyBuildings: enemy building detection deferred
                List.of(),   // enemyStagingArea: not applicable for real SC2
                geysers,
                mineralPatches,
                obs.getGameLoop(),
                mapInfo
        );}

    public static List<NeutralFeature> extractNeutralFeatures(
            Set<com.github.ocraft.s2client.protocol.unit.Unit> allUnits) {
        return allUnits.stream()
                       .filter(u -> u.getAlliance() == Alliance.NEUTRAL)
                       .filter(u -> mapNeutralFeatureType(toUnitsEnum(u)) != null)
                       .map(u -> {
                           var pos = u.getPosition();
                           return new NeutralFeature(
                                   String.valueOf(u.getTag().getValue()),
                                   mapNeutralFeatureType(toUnitsEnum(u)),
                                   new Point2d(pos.getX(), pos.getY())
                           );
                       })
                       .toList();
    }


    static boolean isBuilding(Units type) {
        return ALL_BUILDINGS.contains(type);
    }

    static boolean isGeyser(Units type) {
        return ALL_GEYSERS.contains(type);
    }

    static boolean isMineralPatch(Units type) {
        return ALL_MINERAL_PATCHES.contains(type);
    }

    public static NeutralFeatureType mapNeutralFeatureType(Units type) {
        if (type == Units.NEUTRAL_XELNAGA_TOWER) {return NeutralFeatureType.XELNAGA_TOWER;}
        String name = type.name();
        if (name.startsWith("NEUTRAL_DESTRUCTIBLE")) {return NeutralFeatureType.DESTRUCTIBLE_ROCK;}
        if (name.startsWith("NEUTRAL_UNBUILDABLE")) {return NeutralFeatureType.DESTRUCTIBLE_DEBRIS;}
        return null;
    }

    static int defaultGeyserAmount(Units type) {
        return type == Units.NEUTRAL_RICH_VESPENE_GEYSER ? 2500 : 2250;
    }

    static int defaultMineralAmount(Units type) {
        String name = type.name();
        if (name.contains("450")) {return 450;}
        if (name.contains("750")) {return 750;}
        return 1500;
    }


    public static UnitType fromSc2Id(int sc2UnitTypeId) {
        for (Units u : Units.values()) {
            if (u.getUnitTypeId() == sc2UnitTypeId) return mapUnitType(u);
        }
        return UnitType.UNKNOWN;
    }

    public static UnitType mapUnitType(Units type) {
        return switch (type) {
            case PROTOSS_PROBE        -> UnitType.PROBE;
            case PROTOSS_ZEALOT       -> UnitType.ZEALOT;
            case PROTOSS_STALKER      -> UnitType.STALKER;
            case PROTOSS_IMMORTAL     -> UnitType.IMMORTAL;
            case PROTOSS_COLOSSUS     -> UnitType.COLOSSUS;
            case PROTOSS_CARRIER      -> UnitType.CARRIER;
            case PROTOSS_DARK_TEMPLAR -> UnitType.DARK_TEMPLAR;
            case PROTOSS_HIGH_TEMPLAR -> UnitType.HIGH_TEMPLAR;
            case PROTOSS_ARCHON       -> UnitType.ARCHON;
            case PROTOSS_OBSERVER     -> UnitType.OBSERVER;
            case PROTOSS_VOIDRAY      -> UnitType.VOID_RAY;
            default                   -> UnitType.UNKNOWN;
        };
    }

    static BuildingType mapBuildingType(Units type) {
        return switch (type) {
            // Protoss
            case PROTOSS_NEXUS             -> BuildingType.NEXUS;
            case PROTOSS_PYLON             -> BuildingType.PYLON;
            case PROTOSS_GATEWAY,
                 PROTOSS_WARP_GATE         -> BuildingType.GATEWAY;
            case PROTOSS_CYBERNETICS_CORE  -> BuildingType.CYBERNETICS_CORE;
            case PROTOSS_ASSIMILATOR       -> BuildingType.ASSIMILATOR;
            case PROTOSS_ROBOTICS_FACILITY -> BuildingType.ROBOTICS_FACILITY;
            case PROTOSS_STARGATE          -> BuildingType.STARGATE;
            case PROTOSS_FORGE             -> BuildingType.FORGE;
            case PROTOSS_TWILIGHT_COUNCIL  -> BuildingType.TWILIGHT_COUNCIL;
            case PROTOSS_PHOTON_CANNON     -> BuildingType.PHOTON_CANNON;
            case PROTOSS_SHIELD_BATTERY    -> BuildingType.SHIELD_BATTERY;
            case PROTOSS_DARK_SHRINE       -> BuildingType.DARK_SHRINE;
            case PROTOSS_TEMPLAR_ARCHIVE   -> BuildingType.TEMPLAR_ARCHIVES;
            case PROTOSS_FLEET_BEACON      -> BuildingType.FLEET_BEACON;
            case PROTOSS_ROBOTICS_BAY      -> BuildingType.ROBOTICS_BAY;
            // Terran
            case TERRAN_COMMAND_CENTER     -> BuildingType.COMMAND_CENTER;
            case TERRAN_ORBITAL_COMMAND,
                 TERRAN_ORBITAL_COMMAND_FLYING -> BuildingType.ORBITAL_COMMAND;
            case TERRAN_PLANETARY_FORTRESS -> BuildingType.PLANETARY_FORTRESS;
            case TERRAN_SUPPLY_DEPOT,
                 TERRAN_SUPPLY_DEPOT_LOWERED -> BuildingType.SUPPLY_DEPOT;
            case TERRAN_BARRACKS, TERRAN_BARRACKS_FLYING,
                 TERRAN_BARRACKS_TECHLAB, TERRAN_BARRACKS_REACTOR -> BuildingType.BARRACKS;
            case TERRAN_ENGINEERING_BAY    -> BuildingType.ENGINEERING_BAY;
            case TERRAN_ARMORY             -> BuildingType.ARMORY;
            case TERRAN_MISSILE_TURRET     -> BuildingType.MISSILE_TURRET;
            case TERRAN_BUNKER             -> BuildingType.BUNKER;
            case TERRAN_SENSOR_TOWER       -> BuildingType.SENSOR_TOWER;
            case TERRAN_GHOST_ACADEMY      -> BuildingType.GHOST_ACADEMY;
            case TERRAN_FACTORY, TERRAN_FACTORY_FLYING,
                 TERRAN_FACTORY_TECHLAB, TERRAN_FACTORY_REACTOR -> BuildingType.FACTORY;
            case TERRAN_STARPORT, TERRAN_STARPORT_FLYING,
                 TERRAN_STARPORT_TECHLAB, TERRAN_STARPORT_REACTOR -> BuildingType.STARPORT;
            case TERRAN_FUSION_CORE        -> BuildingType.FUSION_CORE;
            case TERRAN_REFINERY           -> BuildingType.REFINERY;
            // Zerg
            case ZERG_HATCHERY             -> BuildingType.HATCHERY;
            case ZERG_LAIR                 -> BuildingType.LAIR;
            case ZERG_HIVE                 -> BuildingType.HIVE;
            case ZERG_SPAWNING_POOL        -> BuildingType.SPAWNING_POOL;
            case ZERG_EVOLUTION_CHAMBER    -> BuildingType.EVOLUTION_CHAMBER;
            case ZERG_ROACH_WARREN         -> BuildingType.ROACH_WARREN;
            case ZERG_BANELING_NEST        -> BuildingType.BANELING_NEST;
            case ZERG_SPINE_CRAWLER,
                 ZERG_SPINE_CRAWLER_UPROOTED -> BuildingType.SPINE_CRAWLER;
            case ZERG_SPORE_CRAWLER,
                 ZERG_SPORE_CRAWLER_UPROOTED -> BuildingType.SPORE_CRAWLER;
            case ZERG_HYDRALISK_DEN        -> BuildingType.HYDRALISK_DEN;
            case ZERG_LURKER_DEN_MP        -> BuildingType.LURKER_DEN;
            case ZERG_INFESTATION_PIT      -> BuildingType.INFESTATION_PIT;
            case ZERG_SPIRE                -> BuildingType.SPIRE;
            case ZERG_GREATER_SPIRE        -> BuildingType.GREATER_SPIRE;
            case ZERG_NYDUS_NETWORK        -> BuildingType.NYDUS_NETWORK;
            case ZERG_NYDUS_CANAL          -> BuildingType.NYDUS_CANAL;
            case ZERG_ULTRALISK_CAVERN     -> BuildingType.ULTRALISK_CAVERN;
            case ZERG_EXTRACTOR            -> BuildingType.EXTRACTOR;
            default                        -> BuildingType.UNKNOWN;
        };
    }

    // Protocol Unit is fully-qualified to distinguish from domain Unit.
    private static Units toUnitsEnum(com.github.ocraft.s2client.protocol.unit.Unit u) {
        var rawType = u.getType();
        return rawType instanceof Units enumVal ? enumVal : Units.INVALID;
    }

    private static Unit toUnit(com.github.ocraft.s2client.protocol.unit.Unit u) {
        var pos = u.getPosition();  // com.github.ocraft.s2client.protocol.spatial.Point (3D)
        return new Unit(
            String.valueOf(u.getTag().getValue()),
            mapUnitType(toUnitsEnum(u)),
            new Point2d(pos.getX(), pos.getY()),
            u.getHealth().map(Float::intValue).orElse(0),
            u.getHealthMax().map(Float::intValue).orElse(0),
            u.getShield().map(Float::intValue).orElse(0),
            u.getShieldMax().map(Float::intValue).orElse(0),
            0,  // TODO #70: weapon cooldown — not yet tracked in real SC2 mode
            0   // blinkCooldownTicks — not yet tracked in real SC2 mode
        );
    }

    private static Building toBuilding(com.github.ocraft.s2client.protocol.unit.Unit u) {
        var pos = u.getPosition();
        return new Building(
            String.valueOf(u.getTag().getValue()),
            mapBuildingType(toUnitsEnum(u)),
            new Point2d(pos.getX(), pos.getY()),
            u.getHealth().map(Float::intValue).orElse(0),
            u.getHealthMax().map(Float::intValue).orElse(0),
            u.getBuildProgress() >= 1.0f
        );
    }

    private static Resource toResource(com.github.ocraft.s2client.protocol.unit.Unit u, int amount) {
        var pos = u.getPosition();
        return new Resource(
                String.valueOf(u.getTag().getValue()),
                new Point2d(pos.getX(), pos.getY()),
                amount
        );
    }

}
