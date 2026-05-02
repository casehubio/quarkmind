package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class EnemyStrategyLibraryTest {

    @Test
    void forName_knownStrategy_returnsIt() {
        EnemyStrategy s = EnemyStrategyLibrary.forName("PROTOSS_4GATE");
        assertThat(s).isNotNull();
        assertThat(s.name()).isEqualTo("PROTOSS_4GATE");
        assertThat(s.race()).isEqualTo(Race.PROTOSS);
    }

    @Test
    void forName_unknownName_throwsIllegalArgument() {
        assertThatThrownBy(() -> EnemyStrategyLibrary.forName("DOES_NOT_EXIST"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomForRace_returnsStrategyOfCorrectRace() {
        EnemyStrategy s = EnemyStrategyLibrary.randomForRace(Race.ZERG);
        assertThat(s.race()).isEqualTo(Race.ZERG);
    }

    @Test
    void allNamedStrategiesHaveNonNullAttackConfig() {
        EnemyStrategyLibrary.allNames().forEach(name -> {
            EnemyStrategy s = EnemyStrategyLibrary.forName(name);
            assertThat(s.attackConfig()).as("attackConfig for %s", name).isNotNull();
        });
    }

    @Test
    void allNamedStrategiesHaveNonEmptyBuildOrder() {
        var obs = new EnemyObservation(List.of(), Set.of(), 9999, 0L);
        // REACTIVE delegates to its inner strategy — skip direct nextUnit test here
        EnemyStrategyLibrary.allNames().stream()
            .filter(n -> !n.equals("REACTIVE"))
            .forEach(name -> {
                EnemyStrategy s = EnemyStrategyLibrary.forName(name);
                assertThat(s.nextUnit(obs)).as("nextUnit for %s", name).isPresent();
            });
    }

    @Test
    void libraryContainsAllExpectedStrategies() {
        var names = EnemyStrategyLibrary.allNames();
        assertThat(names).contains(
            "PROTOSS_4GATE", "PROTOSS_BLINK_STALKER", "PROTOSS_COLOSSUS_PUSH",
            "ZERG_ROACH_RUSH", "ZERG_MASS_LING", "ZERG_HYDRA_SWITCH",
            "TERRAN_2RAX_MARINE", "TERRAN_BIO_PUSH", "TERRAN_MECH",
            "REACTIVE"
        );
    }

    @Test
    void randomForRace_protoss_neverReturnsOtherRace() {
        for (int i = 0; i < 20; i++) {
            assertThat(EnemyStrategyLibrary.randomForRace(Race.PROTOSS).race())
                .isEqualTo(Race.PROTOSS);
        }
    }

    @Test
    void forName_returnsIndependentInstances_withResetBuildIndex() {
        EnemyObservation obs = new EnemyObservation(List.of(), Set.of(), 9999, 0L);
        EnemyStrategy s1 = EnemyStrategyLibrary.forName("PROTOSS_4GATE");
        s1.nextUnit(obs); // advance s1 once

        EnemyStrategy s2 = EnemyStrategyLibrary.forName("PROTOSS_4GATE");
        assertThat(s1).isNotSameAs(s2);
        // s2 must always start at index 0 (first unit = ZEALOT for PROTOSS_4GATE)
        assertThat(s2.nextUnit(obs)).contains(UnitType.ZEALOT);
    }
}
