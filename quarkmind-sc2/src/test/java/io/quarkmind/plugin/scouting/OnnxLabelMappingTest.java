package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

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
    }

    @Test
    void resolve_unknownLabel_returnsNull() {
        assertThat(OnnxLabelMapping.resolve("NONEXISTENT", Race.TERRAN)).isNull();
    }

    @Test
    void labelsForRace_matchesLabelCount() {
        assertThat(OnnxLabelMapping.labelsForRace(Race.TERRAN)).hasSize(5);
        assertThat(OnnxLabelMapping.labelsForRace(Race.ZERG)).hasSize(6);
        assertThat(OnnxLabelMapping.labelsForRace(Race.PROTOSS)).hasSize(7);
    }
}
