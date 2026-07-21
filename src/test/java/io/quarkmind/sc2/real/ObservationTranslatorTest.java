package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Units;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.NeutralFeatureType;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationTranslatorTest {

    @Test
    void mapsProtossUnitTypes() {
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_PROBE))
            .isEqualTo(UnitType.PROBE);
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_ZEALOT))
            .isEqualTo(UnitType.ZEALOT);
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_STALKER))
            .isEqualTo(UnitType.STALKER);
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_NEXUS))
            .isEqualTo(UnitType.UNKNOWN); // buildings go through mapBuildingType, not mapUnitType
    }

    @Test
    void mapsProtossBuildingTypes() {
        assertThat(ObservationTranslator.mapBuildingType(Units.PROTOSS_NEXUS))
            .isEqualTo(BuildingType.NEXUS);
        assertThat(ObservationTranslator.mapBuildingType(Units.PROTOSS_PYLON))
            .isEqualTo(BuildingType.PYLON);
        assertThat(ObservationTranslator.mapBuildingType(Units.PROTOSS_PROBE))
            .isEqualTo(BuildingType.UNKNOWN); // units go through mapUnitType
    }

    @Test
    void knowsWhichTypesAreBuildings() {
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_NEXUS)).isTrue();
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_PYLON)).isTrue();
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_PROBE)).isFalse();
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_ZEALOT)).isFalse();
    }

    @Test
    void mapsXelNagaTowerToNeutralFeature() {
        assertThat(ObservationTranslator.mapNeutralFeatureType(Units.NEUTRAL_XELNAGA_TOWER))
                .isEqualTo(NeutralFeatureType.XELNAGA_TOWER);
    }

    @Test
    void mapsDestructibleRockToNeutralFeature() {
        assertThat(ObservationTranslator.mapNeutralFeatureType(Units.NEUTRAL_DESTRUCTIBLE_ROCK6X6))
                .isEqualTo(NeutralFeatureType.DESTRUCTIBLE_ROCK);
        assertThat(ObservationTranslator.mapNeutralFeatureType(Units.NEUTRAL_DESTRUCTIBLE_DEBRIS4X4))
                .isEqualTo(NeutralFeatureType.DESTRUCTIBLE_ROCK);
    }

    @Test
    void mapsUnbuildableDebrisToNeutralFeature() {
        assertThat(ObservationTranslator.mapNeutralFeatureType(Units.NEUTRAL_UNBUILDABLE_ROCKS_DESTRUCTIBLE))
                .isEqualTo(NeutralFeatureType.DESTRUCTIBLE_DEBRIS);
        assertThat(ObservationTranslator.mapNeutralFeatureType(Units.NEUTRAL_UNBUILDABLE_BRICKS_DESTRUCTIBLE))
                .isEqualTo(NeutralFeatureType.DESTRUCTIBLE_DEBRIS);
    }

    @Test
    void identifiesGeyserTypes() {
        assertThat(ObservationTranslator.isGeyser(Units.NEUTRAL_VESPENE_GEYSER)).isTrue();
        assertThat(ObservationTranslator.isGeyser(Units.NEUTRAL_RICH_VESPENE_GEYSER)).isTrue();
        assertThat(ObservationTranslator.isGeyser(Units.NEUTRAL_PROTOSS_VESPENE_GEYSER)).isTrue();
        assertThat(ObservationTranslator.isGeyser(Units.NEUTRAL_XELNAGA_TOWER)).isFalse();
    }

    @Test
    void identifiesMineralPatchTypes() {
        assertThat(ObservationTranslator.isMineralPatch(Units.NEUTRAL_MINERAL_FIELD)).isTrue();
        assertThat(ObservationTranslator.isMineralPatch(Units.NEUTRAL_MINERAL_FIELD750)).isTrue();
        assertThat(ObservationTranslator.isMineralPatch(Units.NEUTRAL_RICH_MINERAL_FIELD)).isTrue();
        assertThat(ObservationTranslator.isMineralPatch(Units.NEUTRAL_XELNAGA_TOWER)).isFalse();
    }

    @Test
    void nonNeutralTypeReturnsNullFeature() {
        assertThat(ObservationTranslator.mapNeutralFeatureType(Units.PROTOSS_PROBE)).isNull();
        assertThat(ObservationTranslator.mapNeutralFeatureType(Units.PROTOSS_NEXUS)).isNull();
    }
}
