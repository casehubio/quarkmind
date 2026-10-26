package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxLabelMappingTest {

    @Test
    void resolve_terranRush_mapsToBroadestArchetype() {
        assertThat(OnnxLabelMapping.resolve("RUSH", Race.TERRAN))
            .isEqualTo(StrategyArchetype.TERRAN_MARINE_RUSH);
    }

    @Test
    void resolve_zergMutaHarass_mapsToExistingMutaliskHarass() {
        assertThat(OnnxLabelMapping.resolve("MUTA_HARASS", Race.ZERG))
            .isEqualTo(StrategyArchetype.ZERG_MUTALISK_HARASS);
    }

    @Test
    void resolve_protossAllLabels() {
        assertThat(OnnxLabelMapping.resolve("RUSH", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_GATEWAY_RUSH);
        assertThat(OnnxLabelMapping.resolve("PROXY", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_PROXY_GATE);
        assertThat(OnnxLabelMapping.resolve("CANNON_RUSH", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_CANNON_RUSH);
        assertThat(OnnxLabelMapping.resolve("DT_RUSH", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_DT_RUSH);
        assertThat(OnnxLabelMapping.resolve("BLINK_STALKER", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_BLINK_STALKER);
        assertThat(OnnxLabelMapping.resolve("COLOSSUS_PUSH", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_COLOSSUS_PUSH);
        assertThat(OnnxLabelMapping.resolve("AIR_SUPERIORITY", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_AIR_SUPERIORITY);
        assertThat(OnnxLabelMapping.resolve("TECH_RUSH", Race.PROTOSS))
            .isEqualTo(StrategyArchetype.PROTOSS_TECH_RUSH);
    }

    @Test
    void resolve_newLabels_mapCorrectly() {
        assertThat(OnnxLabelMapping.resolve("PROXY", Race.TERRAN))
                .isEqualTo(StrategyArchetype.TERRAN_PROXY_BARRACKS);
        assertThat(OnnxLabelMapping.resolve("MACRO_ECONOMY", Race.TERRAN))
                .isEqualTo(StrategyArchetype.TERRAN_FAST_EXPAND);
        assertThat(OnnxLabelMapping.resolve("TECH_RUSH", Race.ZERG))
                .isEqualTo(StrategyArchetype.ZERG_TECH_RUSH);
        assertThat(OnnxLabelMapping.resolve("MACRO_ECONOMY", Race.PROTOSS))
                .isEqualTo(StrategyArchetype.PROTOSS_MACRO);
    }


    @Test
    void resolve_unknownLabel_returnsNull() {
        assertThat(OnnxLabelMapping.resolve("NONEXISTENT", Race.TERRAN)).isNull();
    }

    @Test
    void labelsForRace_matchesLabelCount() {
        assertThat(OnnxLabelMapping.labelsForRace(Race.TERRAN)).hasSize(8);
        assertThat(OnnxLabelMapping.labelsForRace(Race.ZERG)).hasSize(7);
        assertThat(OnnxLabelMapping.labelsForRace(Race.PROTOSS)).hasSize(9);
    }
}
