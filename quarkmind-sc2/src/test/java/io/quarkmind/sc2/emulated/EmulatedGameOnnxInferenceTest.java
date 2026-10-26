package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.plugin.scouting.MapCharacteristics;
import io.quarkmind.plugin.scouting.StrategyFeatureExtractor;
import io.quarkmind.plugin.scouting.StrategyFeatures;
import io.quarkmind.plugin.scouting.TemporalWindowAccumulator;
import io.quarkmind.plugin.scouting.WindowSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmulatedGameOnnxInferenceTest {

    private static final int SPATIAL_OFFSET = 204;
    private static final int N_TICK_FEATURES = 214;

    @Test
    void featurePipelineProducesValidStrategyFeatures() {
        var game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT);
        game.reset();

        var accumulator = new TemporalWindowAccumulator();
        for (int tick = 0; tick < 4500; tick++) {
            game.tick();
            if (tick % 60 == 0 && tick > 0) {
                var gs = game.snapshot();
                accumulator.addSnapshot(buildWindowSnapshot(gs));
            }
        }

        var windowedFeatures = accumulator.getWindowedFeatures();
        assertThat(windowedFeatures).isNotEmpty();

        var extractor = new StrategyFeatureExtractor();
        var features = extractor.extract(windowedFeatures, MapCharacteristics.DEFAULT);

        assertThat(features).isNotNull();
        assertThat(features.tensors()).containsKeys("temporal", "map");
        var temporal = features.tensors().get("temporal");
        assertThat(temporal).isNotNull();
        assertThat(temporal.length).isEqualTo(1);
        assertThat(temporal[0].length).isGreaterThan(0);

        var map = features.tensors().get("map");
        assertThat(map).isNotNull();
        assertThat(map.length).isEqualTo(1);

        boolean hasNonZero = false;
        for (float v : temporal[0]) {
            if (v != 0.0f) { hasNonZero = true; break; }
        }
        assertThat(hasNonZero).as("Temporal features should contain non-zero values").isTrue();
    }

    @Test
    void featureVectorsHaveCorrectDimensions() {
        var game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT);
        game.reset();

        var accumulator = new TemporalWindowAccumulator();
        for (int tick = 0; tick < 4500; tick++) {
            game.tick();
            if (tick % 60 == 0 && tick > 0) {
                accumulator.addSnapshot(buildWindowSnapshot(game.snapshot()));
            }
        }

        var windowedFeatures = accumulator.getWindowedFeatures();
        assertThat(windowedFeatures).hasSize(10);

        for (var window : windowedFeatures) {
            assertThat(window.length).as("Each window should have FEATURES_PER_WINDOW floats")
                    .isGreaterThanOrEqualTo(SPATIAL_OFFSET * 2);
        }
    }

    private WindowSnapshot buildWindowSnapshot(GameState gs) {
        float[] playerFeatures = buildFeatureArray(
                gs.myBuildings(), gs.myUnits(), gs.playerEconomy(), gs.playerUpgrades());
        float[] opponentFeatures = buildFeatureArray(
                gs.enemyBuildings(), gs.enemyUnits(), gs.enemyEconomy(), gs.enemyUpgrades());
        return new WindowSnapshot(playerFeatures, opponentFeatures, 0.0f);
    }

    private float[] buildFeatureArray(List<Building> buildings, List<Unit> units,
                                      PlayerEconomyStats economy, Set<String> upgrades) {
        float[] features = new float[N_TICK_FEATURES];
        for (var b : buildings) {
            int idx = buildingIndex(b.type());
            if (idx >= 0) features[idx]++;
        }
        int unitBase = 53;
        for (var u : units) {
            int idx = unitIndex(u.type());
            if (idx >= 0) features[unitBase + idx]++;
        }
        int statsBase = 53 + 56;
        float[] ecoVec = economy.toFeatureVector();
        System.arraycopy(ecoVec, 0, features, statsBase, ecoVec.length);
        return features;
    }

    private static int buildingIndex(BuildingType type) {
        return switch (type) {
            case COMMAND_CENTER -> 0; case ORBITAL_COMMAND -> 1; case PLANETARY_FORTRESS -> 2;
            case BARRACKS -> 3; case FACTORY -> 4; case STARPORT -> 5;
            case ENGINEERING_BAY -> 6; case ARMORY -> 7; case GHOST_ACADEMY -> 8;
            case FUSION_CORE -> 9; case BUNKER -> 10; case MISSILE_TURRET -> 11;
            case SENSOR_TOWER -> 12; case SUPPLY_DEPOT -> 13; case REFINERY -> 14;
            case HATCHERY -> 21; case LAIR -> 22; case HIVE -> 23;
            case SPAWNING_POOL -> 24; case BANELING_NEST -> 25; case ROACH_WARREN -> 26;
            case EVOLUTION_CHAMBER -> 27; case EXTRACTOR -> 28; case HYDRALISK_DEN -> 29;
            case SPINE_CRAWLER -> 30; case SPORE_CRAWLER -> 31; case SPIRE -> 32;
            case GREATER_SPIRE -> 33; case INFESTATION_PIT -> 34; case NYDUS_NETWORK -> 35;
            case ULTRALISK_CAVERN -> 36; case NEXUS -> 37; case GATEWAY -> 38;
            case CYBERNETICS_CORE -> 40; case FORGE -> 41; case ASSIMILATOR -> 42;
            case PYLON -> 43; case PHOTON_CANNON -> 44; case SHIELD_BATTERY -> 45;
            case ROBOTICS_FACILITY -> 46; case ROBOTICS_BAY -> 47; case STARGATE -> 48;
            case FLEET_BEACON -> 49; case TWILIGHT_COUNCIL -> 50; case TEMPLAR_ARCHIVES -> 51;
            case DARK_SHRINE -> 52;
            default -> -1;
        };
    }

    private static int unitIndex(UnitType type) {
        return switch (type) {
            case SCV -> 0; case MARINE -> 1; case MARAUDER -> 2; case REAPER -> 3;
            case GHOST -> 4; case HELLION -> 5; case HELLBAT -> 6;
            case SIEGE_TANK -> 7; case CYCLONE -> 8; case THOR -> 9;
            case MEDIVAC -> 10; case VIKING -> 11; case VIKING_ASSAULT -> 12;
            case LIBERATOR -> 13; case BANSHEE -> 14; case RAVEN -> 15;
            case WIDOW_MINE -> 16;
            case DRONE -> 17; case ZERGLING -> 18; case BANELING -> 19;
            case ROACH -> 20; case RAVAGER -> 21; case QUEEN -> 22;
            case MUTALISK -> 23; case CORRUPTOR -> 24; case BROOD_LORD -> 25;
            case HYDRALISK -> 26; case LURKER -> 27; case INFESTOR -> 28;
            case SWARM_HOST -> 29; case ULTRALISK -> 30; case VIPER -> 31;
            case OVERLORD -> 32; case OVERSEER -> 33;
            case PROBE -> 34; case ZEALOT -> 35; case STALKER -> 36;
            case SENTRY -> 37; case ADEPT -> 38; case HIGH_TEMPLAR -> 39;
            case DARK_TEMPLAR -> 40; case ARCHON -> 41; case IMMORTAL -> 42;
            case COLOSSUS -> 43; case DISRUPTOR -> 44; case WARP_PRISM -> 45;
            case PHOENIX -> 46; case ORACLE -> 47; case VOID_RAY -> 48;
            case CARRIER -> 49; case TEMPEST -> 50; case MOTHERSHIP -> 51;
            case OBSERVER -> 52; case LARVA -> 53; case MULE -> 54;
            case INTERCEPTOR -> 55;
            default -> -1;
        };
    }
}
