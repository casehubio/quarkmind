package io.quarkmind.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class EnemyArchetypeTest {

    @ParameterizedTest
    @EnumSource(value = EnemyArchetype.class, names = {
        "TERRAN_MARINE_RUSH", "TERRAN_BIO_TIMING", "TERRAN_MECH_PUSH", "TERRAN_BANSHEE_HARASS"
    })
    void terranArchetypes_haveTerranRace(EnemyArchetype arch) {
        assertThat(arch.race()).isEqualTo(Race.TERRAN);
    }

    @ParameterizedTest
    @EnumSource(value = EnemyArchetype.class, names = {
        "ZERG_ZERGLING_RUSH", "ZERG_ROACH_RUSH", "ZERG_MACRO"
    })
    void zergArchetypes_haveZergRace(EnemyArchetype arch) {
        assertThat(arch.race()).isEqualTo(Race.ZERG);
    }

    @ParameterizedTest
    @EnumSource(value = EnemyArchetype.class, names = {
        "PROTOSS_GATEWAY_RUSH", "PROTOSS_CANNON_RUSH", "PROTOSS_MACRO"
    })
    void protossArchetypes_haveProtossRace(EnemyArchetype arch) {
        assertThat(arch.race()).isEqualTo(Race.PROTOSS);
    }

    @ParameterizedTest
    @EnumSource(EnemyArchetype.class)
    void everyArchetype_hasRace(EnemyArchetype arch) {
        assertThat(arch.race()).isNotNull();
    }
}
