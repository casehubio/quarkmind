package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class UnitTypeTest {

    @ParameterizedTest
    @EnumSource(value = UnitType.class, names = {
        "PROBE", "ZEALOT", "STALKER", "IMMORTAL", "COLOSSUS", "CARRIER",
        "DARK_TEMPLAR", "HIGH_TEMPLAR", "ARCHON", "OBSERVER", "VOID_RAY",
        "ADEPT", "DISRUPTOR", "SENTRY", "PHOENIX", "ORACLE", "TEMPEST",
        "MOTHERSHIP", "WARP_PRISM", "WARP_PRISM_PHASING", "INTERCEPTOR",
        "ADEPT_PHASE_SHIFT"
    })
    void protossUnits_haveProtossRace(UnitType type) {
        assertThat(type.race()).isEqualTo(Race.PROTOSS);
    }

    @ParameterizedTest
    @EnumSource(value = UnitType.class, names = {
        "ZERGLING", "ROACH", "HYDRALISK", "MUTALISK", "ULTRALISK",
        "BROOD_LORD", "CORRUPTOR", "INFESTOR", "SWARM_HOST", "VIPER",
        "QUEEN", "RAVAGER", "LURKER", "DRONE", "OVERLORD", "OVERSEER",
        "BANELING", "LOCUST", "BROODLING", "INFESTED_TERRAN", "CHANGELING",
        "EGG"
    })
    void zergUnits_haveZergRace(UnitType type) {
        assertThat(type.race()).isEqualTo(Race.ZERG);
    }

    @ParameterizedTest
    @EnumSource(value = UnitType.class, names = {
        "MARINE", "MARAUDER", "MEDIVAC", "SIEGE_TANK", "SIEGE_TANK_SIEGED",
        "THOR", "VIKING", "GHOST", "RAVEN", "BANSHEE", "BATTLECRUISER",
        "CYCLONE", "LIBERATOR", "WIDOW_MINE", "SCV", "REAPER", "HELLION",
        "HELLBAT", "MULE", "VIKING_ASSAULT", "LIBERATOR_AG", "AUTO_TURRET"
    })
    void terranUnits_haveTerranRace(UnitType type) {
        assertThat(type.race()).isEqualTo(Race.TERRAN);
    }

    @Test
    void unknown_hasNullRace() {
        assertThat(UnitType.UNKNOWN.race()).isNull();
    }

    @ParameterizedTest
    @EnumSource(UnitType.class)
    void everyUnitType_hasExplicitRaceMapping(UnitType type) {
        if (type == UnitType.UNKNOWN) {
            assertThat(type.race()).isNull();
        } else {
            assertThat(type.race()).isNotNull();
        }
    }
}
