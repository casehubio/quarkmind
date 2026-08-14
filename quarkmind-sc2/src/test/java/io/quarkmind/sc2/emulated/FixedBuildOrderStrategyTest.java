package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class FixedBuildOrderStrategyTest {

    static EnemyObservation obs(int minerals) {
        return new EnemyObservation(List.of(), Set.of(), minerals, 0L);
    }

    static EnemyAttackConfig defaultAttack() {
        return new EnemyAttackConfig(3, 200, 30, 50);
    }

    @Test
    void nextUnit_returnsFirstUnit() {
        var s = new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT, UnitType.STALKER), 2, defaultAttack());
        assertThat(s.nextUnit(obs(100))).contains(UnitType.ZEALOT);
    }

    @Test
    void nextUnit_advancesOnEachCall() {
        var s = new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT, UnitType.STALKER), 2, defaultAttack());
        s.nextUnit(obs(200));
        assertThat(s.nextUnit(obs(200))).contains(UnitType.STALKER);
    }

    @Test
    void nextUnit_loopsAroundBuildOrder() {
        var s = new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT), 2, defaultAttack());
        s.nextUnit(obs(100));
        assertThat(s.nextUnit(obs(100))).contains(UnitType.ZEALOT);
    }

    @Test
    void nextUnit_emptyBuildOrder_returnsEmpty() {
        var s = new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(), 2, defaultAttack());
        assertThat(s.nextUnit(obs(0))).isEmpty();
    }

    @Test
    void shouldSwitch_alwaysFalse() {
        var s = new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT), 2, defaultAttack());
        assertThat(s.shouldSwitch(obs(0))).isFalse();
    }

    @Test
    void reset_resetsToFirstUnit() {
        var s = new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT, UnitType.STALKER), 2, defaultAttack());
        s.nextUnit(obs(100));
        s.reset();
        assertThat(s.nextUnit(obs(100))).contains(UnitType.ZEALOT);
    }

    @Test
    void name_returnsConstructorValue() {
        var s = new FixedBuildOrderStrategy("MY_STRAT", Race.ZERG,
            List.of(UnitType.ZERGLING), 3, defaultAttack());
        assertThat(s.name()).isEqualTo("MY_STRAT");
        assertThat(s.race()).isEqualTo(Race.ZERG);
        assertThat(s.mineralsPerTick()).isEqualTo(3);
        assertThat(s.attackConfig()).isEqualTo(defaultAttack());
    }
}
