package io.quarkmind.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyArchetypeTest {

    @ParameterizedTest
    @EnumSource(value = StrategyArchetype.class, names = {
        "TERRAN_MARINE_RUSH", "TERRAN_BIO_TIMING", "TERRAN_MECH_PUSH", "TERRAN_BANSHEE_HARASS"
    })
    void terranArchetypes_haveTerranRace(StrategyArchetype arch) {
        assertThat(arch.race()).isEqualTo(Race.TERRAN);
    }

    @ParameterizedTest
    @EnumSource(value = StrategyArchetype.class, names = {
        "ZERG_ZERGLING_RUSH", "ZERG_ROACH_RUSH", "ZERG_MACRO"
    })
    void zergArchetypes_haveZergRace(StrategyArchetype arch) {
        assertThat(arch.race()).isEqualTo(Race.ZERG);
    }

    @ParameterizedTest
    @EnumSource(value = StrategyArchetype.class, names = {
        "PROTOSS_GATEWAY_RUSH", "PROTOSS_CANNON_RUSH", "PROTOSS_MACRO"
    })
    void protossArchetypes_haveProtossRace(StrategyArchetype arch) {
        assertThat(arch.race()).isEqualTo(Race.PROTOSS);
    }

    @ParameterizedTest
    @EnumSource(StrategyArchetype.class)
    void everyArchetype_hasRace(StrategyArchetype arch) {
        assertThat(arch.race()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(StrategyArchetype.class)
    void everyArchetype_hasAllFields(StrategyArchetype arch) {
        assertThat(arch.race()).isNotNull();
        assertThat(arch.phase()).isNotNull();
        assertThat(arch.category()).isNotNull();
    }

}
