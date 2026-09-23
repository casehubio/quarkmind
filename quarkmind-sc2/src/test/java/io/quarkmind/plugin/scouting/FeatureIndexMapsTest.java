package io.quarkmind.plugin.scouting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureIndexMapsTest {

    @Test
    void featureLayout_constants() {
        assertThat(FeatureIndexMaps.N_BUILDINGS).isEqualTo(53);
        assertThat(FeatureIndexMaps.N_UNITS).isEqualTo(53);
        assertThat(FeatureIndexMaps.N_STATS).isEqualTo(13);
        assertThat(FeatureIndexMaps.N_UPGRADES).isEqualTo(15);
        assertThat(FeatureIndexMaps.N_SPATIAL).isEqualTo(7);
        assertThat(FeatureIndexMaps.N_RATIOS).isEqualTo(3);
        assertThat(FeatureIndexMaps.N_DELTAS).isEqualTo(4);
    }

    @Test
    void featureLayout_perPlayerSizes() {
        assertThat(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER)
            .as("134 original + 7 spatial + 3 ratios = 144")
            .isEqualTo(144);
        assertThat(FeatureIndexMaps.N_FEATURES_PER_PLAYER)
            .as("144 tick features + 4 deltas = 148")
            .isEqualTo(148);
    }

    @Test
    void featureLayout_windowSize() {
        assertThat(FeatureIndexMaps.FEATURES_PER_WINDOW)
            .as("2 * 148 + army_gap + has_vision = 298")
            .isEqualTo(298);
    }

    @Test
    void featureLayout_offsets() {
        assertThat(FeatureIndexMaps.SPATIAL_OFFSET)
            .as("after buildings(53) + units(53) + economy(13) + upgrades(15)")
            .isEqualTo(134);
        assertThat(FeatureIndexMaps.RATIO_OFFSET)
            .as("after spatial(7)")
            .isEqualTo(141);
        assertThat(FeatureIndexMaps.DELTA_OFFSET)
            .as("after ratios(3)")
            .isEqualTo(144);
    }

    @Test
    void featureLayout_crossPlayerIndices() {
        assertThat(FeatureIndexMaps.ARMY_GAP_INDEX)
            .as("after both player blocks: 2 * 148 = 296")
            .isEqualTo(296);
        assertThat(FeatureIndexMaps.HAS_VISION_INDEX)
            .as("last feature: 297")
            .isEqualTo(297);
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
