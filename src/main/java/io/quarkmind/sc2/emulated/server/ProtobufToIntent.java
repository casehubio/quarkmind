package io.quarkmind.sc2.emulated.server;

import SC2APIProtocol.Raw;
import com.github.ocraft.s2client.protocol.data.Abilities;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates SC2 protobuf ActionRawUnitCommand into QuarkMind Intent domain objects.
 *
 * Pure static translator — mirrors the design of ActionTranslator (Intent → protobuf)
 * but in the reverse direction (protobuf → Intent). Used by EmulatedSC2Server for
 * RequestAction handling.
 *
 * Build and Train ability mappings are derived from ActionTranslator.mapBuildAbility()
 * and ActionTranslator.mapTrainAbility() — cannot call them directly because they're
 * package-private in sc2.real.
 */
public final class ProtobufToIntent {

    private ProtobufToIntent() {}

    @FunctionalInterface
    interface IntentFactory {
        Intent create(String tag, Raw.ActionRawUnitCommand cmd);
    }

    private static final Map<Integer, IntentFactory> ABILITY_TO_INTENT = new HashMap<>();

    static {
        // Attack, Move, Blink, MULE
        ABILITY_TO_INTENT.put(Abilities.ATTACK.getAbilityId(),
            (tag, cmd) -> new AttackIntent(tag, toPoint(cmd)));
        ABILITY_TO_INTENT.put(Abilities.MOVE.getAbilityId(),
            (tag, cmd) -> new MoveIntent(tag, toPoint(cmd)));
        ABILITY_TO_INTENT.put(Abilities.EFFECT_BLINK_STALKER.getAbilityId(),
            (tag, cmd) -> new BlinkIntent(tag));
        // MULE calldown — hardcoded ability ID 3632 (EFFECT_CALLDOWNMULE)
        // ActionTranslator.muleCalldown() not yet wired to real SC2
        ABILITY_TO_INTENT.put(3632,
            (tag, cmd) -> new MuleCalldownIntent(tag));

        // Build abilities — replicate ActionTranslator.mapBuildAbility() mappings
        registerBuildAbility(Abilities.BUILD_NEXUS, BuildingType.NEXUS);
        registerBuildAbility(Abilities.BUILD_PYLON, BuildingType.PYLON);
        registerBuildAbility(Abilities.BUILD_GATEWAY, BuildingType.GATEWAY);
        registerBuildAbility(Abilities.BUILD_CYBERNETICS_CORE, BuildingType.CYBERNETICS_CORE);
        registerBuildAbility(Abilities.BUILD_ASSIMILATOR, BuildingType.ASSIMILATOR);
        registerBuildAbility(Abilities.BUILD_ROBOTICS_FACILITY, BuildingType.ROBOTICS_FACILITY);
        registerBuildAbility(Abilities.BUILD_STARGATE, BuildingType.STARGATE);
        registerBuildAbility(Abilities.BUILD_FORGE, BuildingType.FORGE);
        registerBuildAbility(Abilities.BUILD_TWILIGHT_COUNCIL, BuildingType.TWILIGHT_COUNCIL);
        registerBuildAbility(Abilities.BUILD_PHOTON_CANNON, BuildingType.PHOTON_CANNON);
        registerBuildAbility(Abilities.BUILD_SHIELD_BATTERY, BuildingType.SHIELD_BATTERY);
        registerBuildAbility(Abilities.BUILD_DARK_SHRINE, BuildingType.DARK_SHRINE);
        registerBuildAbility(Abilities.BUILD_FLEET_BEACON, BuildingType.FLEET_BEACON);
        registerBuildAbility(Abilities.BUILD_ROBOTICS_BAY, BuildingType.ROBOTICS_BAY);
        registerBuildAbility(Abilities.BUILD_COMMAND_CENTER, BuildingType.COMMAND_CENTER);
        registerBuildAbility(Abilities.MORPH_ORBITAL_COMMAND, BuildingType.ORBITAL_COMMAND);
        registerBuildAbility(Abilities.MORPH_PLANETARY_FORTRESS, BuildingType.PLANETARY_FORTRESS);
        registerBuildAbility(Abilities.BUILD_SUPPLY_DEPOT, BuildingType.SUPPLY_DEPOT);
        registerBuildAbility(Abilities.BUILD_BARRACKS, BuildingType.BARRACKS);
        registerBuildAbility(Abilities.BUILD_ENGINEERING_BAY, BuildingType.ENGINEERING_BAY);
        registerBuildAbility(Abilities.BUILD_ARMORY, BuildingType.ARMORY);
        registerBuildAbility(Abilities.BUILD_MISSILE_TURRET, BuildingType.MISSILE_TURRET);
        registerBuildAbility(Abilities.BUILD_BUNKER, BuildingType.BUNKER);
        registerBuildAbility(Abilities.BUILD_SENSOR_TOWER, BuildingType.SENSOR_TOWER);
        registerBuildAbility(Abilities.BUILD_GHOST_ACADEMY, BuildingType.GHOST_ACADEMY);
        registerBuildAbility(Abilities.BUILD_FACTORY, BuildingType.FACTORY);
        registerBuildAbility(Abilities.BUILD_STARPORT, BuildingType.STARPORT);
        registerBuildAbility(Abilities.BUILD_FUSION_CORE, BuildingType.FUSION_CORE);
        registerBuildAbility(Abilities.BUILD_REFINERY, BuildingType.REFINERY);
        registerBuildAbility(Abilities.BUILD_HATCHERY, BuildingType.HATCHERY);
        registerBuildAbility(Abilities.MORPH_LAIR, BuildingType.LAIR);
        registerBuildAbility(Abilities.MORPH_HIVE, BuildingType.HIVE);
        registerBuildAbility(Abilities.BUILD_SPAWNING_POOL, BuildingType.SPAWNING_POOL);
        registerBuildAbility(Abilities.BUILD_EVOLUTION_CHAMBER, BuildingType.EVOLUTION_CHAMBER);
        registerBuildAbility(Abilities.BUILD_ROACH_WARREN, BuildingType.ROACH_WARREN);
        registerBuildAbility(Abilities.BUILD_BANELING_NEST, BuildingType.BANELING_NEST);
        registerBuildAbility(Abilities.BUILD_SPINE_CRAWLER, BuildingType.SPINE_CRAWLER);
        registerBuildAbility(Abilities.BUILD_SPORE_CRAWLER, BuildingType.SPORE_CRAWLER);
        registerBuildAbility(Abilities.BUILD_HYDRALISK_DEN, BuildingType.HYDRALISK_DEN);
        registerBuildAbility(Abilities.BUILD_INFESTATION_PIT, BuildingType.INFESTATION_PIT);
        registerBuildAbility(Abilities.BUILD_SPIRE, BuildingType.SPIRE);
        registerBuildAbility(Abilities.MORPH_GREATER_SPIRE, BuildingType.GREATER_SPIRE);
        registerBuildAbility(Abilities.BUILD_NYDUS_NETWORK, BuildingType.NYDUS_NETWORK);
        registerBuildAbility(Abilities.BUILD_ULTRALISK_CAVERN, BuildingType.ULTRALISK_CAVERN);
        registerBuildAbility(Abilities.BUILD_EXTRACTOR, BuildingType.EXTRACTOR);

        // Train abilities — replicate ActionTranslator.mapTrainAbility() mappings
        registerTrainAbility(Abilities.TRAIN_PROBE, UnitType.PROBE);
        registerTrainAbility(Abilities.TRAIN_ZEALOT, UnitType.ZEALOT);
        registerTrainAbility(Abilities.TRAIN_STALKER, UnitType.STALKER);
        registerTrainAbility(Abilities.TRAIN_IMMORTAL, UnitType.IMMORTAL);
        registerTrainAbility(Abilities.TRAIN_COLOSSUS, UnitType.COLOSSUS);
        registerTrainAbility(Abilities.TRAIN_CARRIER, UnitType.CARRIER);
        registerTrainAbility(Abilities.TRAIN_DARK_TEMPLAR, UnitType.DARK_TEMPLAR);
        registerTrainAbility(Abilities.TRAIN_HIGH_TEMPLAR, UnitType.HIGH_TEMPLAR);
        registerTrainAbility(Abilities.TRAIN_OBSERVER, UnitType.OBSERVER);
        registerTrainAbility(Abilities.TRAIN_VOIDRAY, UnitType.VOID_RAY);
    }

    public static Intent translate(Raw.ActionRawUnitCommand cmd) {
        if (cmd.getUnitTagsCount() == 0) return null;
        String tag = String.valueOf(cmd.getUnitTags(0));
        IntentFactory factory = ABILITY_TO_INTENT.get(cmd.getAbilityId());
        return factory != null ? factory.create(tag, cmd) : null;
    }

    private static Point2d toPoint(Raw.ActionRawUnitCommand cmd) {
        if (!cmd.hasTargetWorldSpacePos()) return new Point2d(0, 0);
        var p = cmd.getTargetWorldSpacePos();
        return new Point2d(p.getX(), p.getY());
    }

    private static void registerBuildAbility(Abilities ability, BuildingType buildingType) {
        ABILITY_TO_INTENT.put(ability.getAbilityId(),
            (tag, cmd) -> new BuildIntent(tag, buildingType, toPoint(cmd)));
    }

    private static void registerTrainAbility(Abilities ability, UnitType unitType) {
        ABILITY_TO_INTENT.put(ability.getAbilityId(),
            (tag, cmd) -> new TrainIntent(tag, unitType));
    }
}
