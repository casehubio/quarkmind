package io.quarkmind.sc2.emulated.server;

import SC2APIProtocol.Common;
import SC2APIProtocol.Raw;
import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.data.Units;
import com.google.protobuf.ByteString;
import io.quarkmind.domain.*;

import java.util.EnumMap;
import java.util.Map;

public final class GameStateToProtobuf {

    private GameStateToProtobuf() {}

    static final Map<UnitType, Units> UNIT_TYPE_TO_PROTO = new EnumMap<>(UnitType.class);
    static final Map<BuildingType, Units> BUILDING_TYPE_TO_PROTO = new EnumMap<>(BuildingType.class);

    static {
        // Protoss units
        UNIT_TYPE_TO_PROTO.put(UnitType.PROBE, Units.PROTOSS_PROBE);
        UNIT_TYPE_TO_PROTO.put(UnitType.ZEALOT, Units.PROTOSS_ZEALOT);
        UNIT_TYPE_TO_PROTO.put(UnitType.STALKER, Units.PROTOSS_STALKER);
        UNIT_TYPE_TO_PROTO.put(UnitType.IMMORTAL, Units.PROTOSS_IMMORTAL);
        UNIT_TYPE_TO_PROTO.put(UnitType.COLOSSUS, Units.PROTOSS_COLOSSUS);
        UNIT_TYPE_TO_PROTO.put(UnitType.CARRIER, Units.PROTOSS_CARRIER);
        UNIT_TYPE_TO_PROTO.put(UnitType.DARK_TEMPLAR, Units.PROTOSS_DARK_TEMPLAR);
        UNIT_TYPE_TO_PROTO.put(UnitType.HIGH_TEMPLAR, Units.PROTOSS_HIGH_TEMPLAR);
        UNIT_TYPE_TO_PROTO.put(UnitType.ARCHON, Units.PROTOSS_ARCHON);
        UNIT_TYPE_TO_PROTO.put(UnitType.OBSERVER, Units.PROTOSS_OBSERVER);
        UNIT_TYPE_TO_PROTO.put(UnitType.VOID_RAY, Units.PROTOSS_VOIDRAY);
        UNIT_TYPE_TO_PROTO.put(UnitType.ADEPT, Units.PROTOSS_ADEPT);
        UNIT_TYPE_TO_PROTO.put(UnitType.DISRUPTOR, Units.PROTOSS_DISRUPTOR);
        UNIT_TYPE_TO_PROTO.put(UnitType.SENTRY, Units.PROTOSS_SENTRY);
        UNIT_TYPE_TO_PROTO.put(UnitType.PHOENIX, Units.PROTOSS_PHOENIX);
        UNIT_TYPE_TO_PROTO.put(UnitType.ORACLE, Units.PROTOSS_ORACLE);
        UNIT_TYPE_TO_PROTO.put(UnitType.TEMPEST, Units.PROTOSS_TEMPEST);
        UNIT_TYPE_TO_PROTO.put(UnitType.MOTHERSHIP, Units.PROTOSS_MOTHERSHIP);
        UNIT_TYPE_TO_PROTO.put(UnitType.WARP_PRISM, Units.PROTOSS_WARP_PRISM);
        UNIT_TYPE_TO_PROTO.put(UnitType.WARP_PRISM_PHASING, Units.PROTOSS_WARP_PRISM_PHASING);
        UNIT_TYPE_TO_PROTO.put(UnitType.INTERCEPTOR, Units.PROTOSS_INTERCEPTOR);
        UNIT_TYPE_TO_PROTO.put(UnitType.ADEPT_PHASE_SHIFT, Units.PROTOSS_ADEPT_PHASE_SHIFT);

        // Zerg units
        UNIT_TYPE_TO_PROTO.put(UnitType.ZERGLING, Units.ZERG_ZERGLING);
        UNIT_TYPE_TO_PROTO.put(UnitType.ROACH, Units.ZERG_ROACH);
        UNIT_TYPE_TO_PROTO.put(UnitType.HYDRALISK, Units.ZERG_HYDRALISK);
        UNIT_TYPE_TO_PROTO.put(UnitType.MUTALISK, Units.ZERG_MUTALISK);
        UNIT_TYPE_TO_PROTO.put(UnitType.ULTRALISK, Units.ZERG_ULTRALISK);
        UNIT_TYPE_TO_PROTO.put(UnitType.BROOD_LORD, Units.ZERG_BROODLORD);
        UNIT_TYPE_TO_PROTO.put(UnitType.CORRUPTOR, Units.ZERG_CORRUPTOR);
        UNIT_TYPE_TO_PROTO.put(UnitType.INFESTOR, Units.ZERG_INFESTOR);
        UNIT_TYPE_TO_PROTO.put(UnitType.SWARM_HOST, Units.ZERG_SWARM_HOST_MP);
        UNIT_TYPE_TO_PROTO.put(UnitType.VIPER, Units.ZERG_VIPER);
        UNIT_TYPE_TO_PROTO.put(UnitType.QUEEN, Units.ZERG_QUEEN);
        UNIT_TYPE_TO_PROTO.put(UnitType.RAVAGER, Units.ZERG_RAVAGER);
        UNIT_TYPE_TO_PROTO.put(UnitType.LURKER, Units.ZERG_LURKER_MP);
        UNIT_TYPE_TO_PROTO.put(UnitType.DRONE, Units.ZERG_DRONE);
        UNIT_TYPE_TO_PROTO.put(UnitType.OVERLORD, Units.ZERG_OVERLORD);
        UNIT_TYPE_TO_PROTO.put(UnitType.OVERSEER, Units.ZERG_OVERSEER);
        UNIT_TYPE_TO_PROTO.put(UnitType.BANELING, Units.ZERG_BANELING);
        // UNIT_TYPE_TO_PROTO.put(UnitType.LOCUST, Units.ZERG_LOCUST_MP_FLYING); // TODO: find correct constant
        UNIT_TYPE_TO_PROTO.put(UnitType.BROODLING, Units.ZERG_BROODLING);
        // UNIT_TYPE_TO_PROTO.put(UnitType.INFESTED_TERRAN, Units.ZERG_INFESTED_TERRANS_EGG); // TODO: find correct constant
        UNIT_TYPE_TO_PROTO.put(UnitType.CHANGELING, Units.ZERG_CHANGELING);
        UNIT_TYPE_TO_PROTO.put(UnitType.EGG, Units.ZERG_EGG);

        // Terran units
        UNIT_TYPE_TO_PROTO.put(UnitType.MARINE, Units.TERRAN_MARINE);
        UNIT_TYPE_TO_PROTO.put(UnitType.MARAUDER, Units.TERRAN_MARAUDER);
        UNIT_TYPE_TO_PROTO.put(UnitType.MEDIVAC, Units.TERRAN_MEDIVAC);
        UNIT_TYPE_TO_PROTO.put(UnitType.SIEGE_TANK, Units.TERRAN_SIEGE_TANK);
        UNIT_TYPE_TO_PROTO.put(UnitType.SIEGE_TANK_SIEGED, Units.TERRAN_SIEGE_TANK_SIEGED);
        UNIT_TYPE_TO_PROTO.put(UnitType.THOR, Units.TERRAN_THOR);
        UNIT_TYPE_TO_PROTO.put(UnitType.VIKING, Units.TERRAN_VIKING_FIGHTER);
        UNIT_TYPE_TO_PROTO.put(UnitType.GHOST, Units.TERRAN_GHOST);
        UNIT_TYPE_TO_PROTO.put(UnitType.RAVEN, Units.TERRAN_RAVEN);
        UNIT_TYPE_TO_PROTO.put(UnitType.BANSHEE, Units.TERRAN_BANSHEE);
        UNIT_TYPE_TO_PROTO.put(UnitType.BATTLECRUISER, Units.TERRAN_BATTLECRUISER);
        UNIT_TYPE_TO_PROTO.put(UnitType.CYCLONE, Units.TERRAN_CYCLONE);
        UNIT_TYPE_TO_PROTO.put(UnitType.LIBERATOR, Units.TERRAN_LIBERATOR);
        // UNIT_TYPE_TO_PROTO.put(UnitType.WIDOW_MINE, Units.TERRAN_WIDOW_MINE_BURROWED); // TODO: find correct constant
        UNIT_TYPE_TO_PROTO.put(UnitType.SCV, Units.TERRAN_SCV);
        UNIT_TYPE_TO_PROTO.put(UnitType.REAPER, Units.TERRAN_REAPER);
        UNIT_TYPE_TO_PROTO.put(UnitType.HELLION, Units.TERRAN_HELLION);
        UNIT_TYPE_TO_PROTO.put(UnitType.HELLBAT, Units.TERRAN_HELLION_TANK);
        UNIT_TYPE_TO_PROTO.put(UnitType.MULE, Units.TERRAN_MULE);
        UNIT_TYPE_TO_PROTO.put(UnitType.VIKING_ASSAULT, Units.TERRAN_VIKING_ASSAULT);
        UNIT_TYPE_TO_PROTO.put(UnitType.LIBERATOR_AG, Units.TERRAN_LIBERATOR_AG);
        UNIT_TYPE_TO_PROTO.put(UnitType.AUTO_TURRET, Units.TERRAN_AUTO_TURRET);

        BUILDING_TYPE_TO_PROTO.put(BuildingType.NEXUS, Units.PROTOSS_NEXUS);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.PYLON, Units.PROTOSS_PYLON);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.GATEWAY, Units.PROTOSS_GATEWAY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.CYBERNETICS_CORE, Units.PROTOSS_CYBERNETICS_CORE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ASSIMILATOR, Units.PROTOSS_ASSIMILATOR);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ROBOTICS_FACILITY, Units.PROTOSS_ROBOTICS_FACILITY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.STARGATE, Units.PROTOSS_STARGATE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.FORGE, Units.PROTOSS_FORGE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.TWILIGHT_COUNCIL, Units.PROTOSS_TWILIGHT_COUNCIL);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.PHOTON_CANNON, Units.PROTOSS_PHOTON_CANNON);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.SHIELD_BATTERY, Units.PROTOSS_SHIELD_BATTERY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.DARK_SHRINE, Units.PROTOSS_DARK_SHRINE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.TEMPLAR_ARCHIVES, Units.PROTOSS_TEMPLAR_ARCHIVE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.FLEET_BEACON, Units.PROTOSS_FLEET_BEACON);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ROBOTICS_BAY, Units.PROTOSS_ROBOTICS_BAY);
        // Terran buildings
        BUILDING_TYPE_TO_PROTO.put(BuildingType.COMMAND_CENTER, Units.TERRAN_COMMAND_CENTER);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ORBITAL_COMMAND, Units.TERRAN_ORBITAL_COMMAND);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.PLANETARY_FORTRESS, Units.TERRAN_PLANETARY_FORTRESS);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.BARRACKS, Units.TERRAN_BARRACKS);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ENGINEERING_BAY, Units.TERRAN_ENGINEERING_BAY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ARMORY, Units.TERRAN_ARMORY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.MISSILE_TURRET, Units.TERRAN_MISSILE_TURRET);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.BUNKER, Units.TERRAN_BUNKER);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.SENSOR_TOWER, Units.TERRAN_SENSOR_TOWER);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.GHOST_ACADEMY, Units.TERRAN_GHOST_ACADEMY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.FACTORY, Units.TERRAN_FACTORY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.STARPORT, Units.TERRAN_STARPORT);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.FUSION_CORE, Units.TERRAN_FUSION_CORE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.REFINERY, Units.TERRAN_REFINERY);
        // Zerg buildings
        BUILDING_TYPE_TO_PROTO.put(BuildingType.HATCHERY, Units.ZERG_HATCHERY);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.LAIR, Units.ZERG_LAIR);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.HIVE, Units.ZERG_HIVE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.SPAWNING_POOL, Units.ZERG_SPAWNING_POOL);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.EVOLUTION_CHAMBER, Units.ZERG_EVOLUTION_CHAMBER);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ROACH_WARREN, Units.ZERG_ROACH_WARREN);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.BANELING_NEST, Units.ZERG_BANELING_NEST);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.SPINE_CRAWLER, Units.ZERG_SPINE_CRAWLER);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.SPORE_CRAWLER, Units.ZERG_SPORE_CRAWLER);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.HYDRALISK_DEN, Units.ZERG_HYDRALISK_DEN);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.LURKER_DEN, Units.ZERG_LURKER_DEN_MP);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.INFESTATION_PIT, Units.ZERG_INFESTATION_PIT);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.SPIRE, Units.ZERG_SPIRE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.GREATER_SPIRE, Units.ZERG_GREATER_SPIRE);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.NYDUS_NETWORK, Units.ZERG_NYDUS_NETWORK);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.NYDUS_CANAL, Units.ZERG_NYDUS_CANAL);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.ULTRALISK_CAVERN, Units.ZERG_ULTRALISK_CAVERN);
        BUILDING_TYPE_TO_PROTO.put(BuildingType.EXTRACTOR, Units.ZERG_EXTRACTOR);
    }

    public static Sc2Api.ResponseObservation translate(GameState state) {
        Raw.ObservationRaw.Builder rawObs = Raw.ObservationRaw.newBuilder();

        // Friendly units (Alliance.Self, not buildings)
        for (Unit u : state.myUnits()) {
            rawObs.addUnits(toProtoUnit(u, Raw.Alliance.Self));
        }
        // Friendly buildings (Alliance.Self, building types)
        for (Building b : state.myBuildings()) {
            rawObs.addUnits(toProtoBuilding(b, Raw.Alliance.Self));
        }
        // Enemy units, buildings, staging — all Alliance.Enemy
        for (Unit u : state.enemyUnits()) {
            rawObs.addUnits(toProtoUnit(u, Raw.Alliance.Enemy));
        }
        for (Building b : state.enemyBuildings()) {
            rawObs.addUnits(toProtoBuilding(b, Raw.Alliance.Enemy));
        }
        for (Unit u : state.enemyStagingArea()) {
            rawObs.addUnits(toProtoUnit(u, Raw.Alliance.Enemy));
        }

        // PlayerRaw with camera — ocraft requires camera (orElseThrow)
        rawObs.setPlayer(Raw.PlayerRaw.newBuilder()
            .setCamera(Common.Point.newBuilder().setX(50).setY(50).build())
            .build());

        // MapState — ocraft requires visibility and creep ImageData
        Common.ImageData emptyImg = Common.ImageData.newBuilder()
            .setBitsPerPixel(8)
            .setSize(Common.Size2DI.newBuilder().setX(1).setY(1).build())
            .setData(ByteString.copyFrom(new byte[]{0}))
            .build();
        rawObs.setMapState(Raw.MapState.newBuilder()
            .setVisibility(emptyImg)
            .setCreep(emptyImg)
            .build());

        Sc2Api.Observation obs = Sc2Api.Observation.newBuilder()
            .setGameLoop((int) state.gameFrame())
            .setPlayerCommon(Sc2Api.PlayerCommon.newBuilder()
                .setPlayerId(1)
                .setMinerals(state.minerals())
                .setVespene(state.vespene())
                .setFoodCap(state.supply())
                .setFoodUsed(state.supplyUsed())
                .setFoodArmy(0)
                .setFoodWorkers(0)
                .setIdleWorkerCount(0)
                .setArmyCount(0)
                .build())
            .setRawData(rawObs.build())
            .build();

        return Sc2Api.ResponseObservation.newBuilder()
            .setObservation(obs)
            .build();
    }

    private static Raw.Unit toProtoUnit(Unit u, Raw.Alliance alliance) {
        Units protoType = UNIT_TYPE_TO_PROTO.getOrDefault(u.type(), Units.INVALID);
        return Raw.Unit.newBuilder()
            .setTag(extractNumericTag(u.tag(), alliance, false))
            .setUnitType(protoType.getUnitTypeId())
            .setDisplayType(Raw.DisplayType.Visible)
            .setAlliance(alliance)
            .setPos(Common.Point.newBuilder()
                .setX(u.position().x()).setY(u.position().y()).setZ(0).build())
            .setHealth(u.health())
            .setHealthMax(u.maxHealth())
            .setShield(u.shields())
            .setShieldMax(u.maxShields())
            .setBuildProgress(1.0f)
            .build();
    }

    private static Raw.Unit toProtoBuilding(Building b, Raw.Alliance alliance) {
        Units protoType = BUILDING_TYPE_TO_PROTO.getOrDefault(b.type(), Units.INVALID);
        return Raw.Unit.newBuilder()
            .setTag(extractNumericTag(b.tag(), alliance, true))
            .setUnitType(protoType.getUnitTypeId())
            .setDisplayType(Raw.DisplayType.Visible)
            .setAlliance(alliance)
            .setPos(Common.Point.newBuilder()
                .setX(b.position().x()).setY(b.position().y()).setZ(0).build())
            .setHealth(b.health())
            .setHealthMax(b.maxHealth())
            .setShield(0).setShieldMax(0)
            .setBuildProgress(b.isComplete() ? 1.0f : 0.5f)
            .build();
    }

    /**
     * Extract numeric portion from EmulatedGame tags.
     * EmulatedGame uses "unit-0", "bldg-0", "enemy-1" etc.
     * SC2 protobuf expects pure numeric tags.
     */
    private static final long UNIT_OFFSET = 0;
    private static final long BUILDING_OFFSET = 100_000;
    private static final long ENEMY_UNIT_OFFSET = 200_000;
    private static final long ENEMY_BUILDING_OFFSET = 300_000;

    static long extractNumericTag(String tag, Raw.Alliance alliance, boolean isBuilding) {
        long base = parseNumericSuffix(tag);
        if (alliance == Raw.Alliance.Enemy) {
            return base + (isBuilding ? ENEMY_BUILDING_OFFSET : ENEMY_UNIT_OFFSET);
        }
        return base + (isBuilding ? BUILDING_OFFSET : UNIT_OFFSET);
    }

    private static long parseNumericSuffix(String tag) {
        int lastDash = tag.lastIndexOf('-');
        if (lastDash >= 0 && lastDash < tag.length() - 1) {
            try {
                return Long.parseLong(tag.substring(lastDash + 1));
            } catch (NumberFormatException e) {
                // Fall through to hash-based tag
            }
        }
        return Math.abs((long) tag.hashCode());
    }
}
