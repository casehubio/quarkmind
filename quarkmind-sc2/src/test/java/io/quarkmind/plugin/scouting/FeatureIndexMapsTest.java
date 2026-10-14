package io.quarkmind.plugin.scouting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureIndexMapsTest {

    @Test
    void featureLayout_constants() {
        assertThat(FeatureIndexMaps.N_BUILDINGS).isEqualTo(53);
        assertThat(FeatureIndexMaps.N_UNITS).isEqualTo(56);
        assertThat(FeatureIndexMaps.N_STATS).isEqualTo(13);
        assertThat(FeatureIndexMaps.N_UPGRADES).isEqualTo(82);
        assertThat(FeatureIndexMaps.N_SPATIAL).isEqualTo(7);
        assertThat(FeatureIndexMaps.N_RATIOS).isEqualTo(3);
        assertThat(FeatureIndexMaps.N_DELTAS).isEqualTo(4);
    }

    @Test
    void featureLayout_perPlayerSizes() {
        assertThat(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER)
            .as("53 + 56 + 13 + 82 + 7 + 3 = 214")
            .isEqualTo(214);
        assertThat(FeatureIndexMaps.N_FEATURES_PER_PLAYER)
            .as("214 tick features + 4 deltas = 218")
            .isEqualTo(218);
    }

    @Test
    void featureLayout_windowSize() {
        assertThat(FeatureIndexMaps.FEATURES_PER_WINDOW)
            .as("2 * 218 + army_gap + has_vision = 438")
            .isEqualTo(438);
    }

    @Test
    void featureLayout_offsets() {
        assertThat(FeatureIndexMaps.SPATIAL_OFFSET)
            .as("after buildings(53) + units(56) + economy(13) + upgrades(82)")
            .isEqualTo(204);
        assertThat(FeatureIndexMaps.RATIO_OFFSET)
            .as("after spatial(7)")
            .isEqualTo(211);
        assertThat(FeatureIndexMaps.DELTA_OFFSET)
            .as("after ratios(3)")
            .isEqualTo(214);
    }

    @Test
    void featureLayout_crossPlayerIndices() {
        assertThat(FeatureIndexMaps.ARMY_GAP_INDEX)
            .as("after both player blocks: 2 * 218 = 436")
            .isEqualTo(436);
        assertThat(FeatureIndexMaps.HAS_VISION_INDEX)
            .as("last feature: 437")
            .isEqualTo(437);
    }

    @Test
    void generateSupplyCosts() throws Exception {
        var costs = new java.util.LinkedHashMap<String, Integer>();
        for (io.quarkmind.domain.UnitType type : io.quarkmind.domain.UnitType.values()) {
            costs.put(type.name(), io.quarkmind.domain.SC2Data.supplyCost(type));
        }
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                              .writerWithDefaultPrettyPrinter()
                              .writeValueAsString(costs);
        java.nio.file.Path out = java.nio.file.Path.of("src/main/resources/classifier/supply_costs.json");
        java.nio.file.Files.writeString(out, json);
        assertThat(out).exists();
        assertThat(costs).isNotEmpty();
        assertThat(costs.get("MARINE")).isEqualTo(1);
        assertThat(costs.get("SIEGE_TANK")).isEqualTo(3);
        assertThat(costs.get("ZERGLING")).isEqualTo(1);
    }
}
