package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EnemyStrategy-related domain types.
 * EnemyStrategy is now an interface; tests for the old record API have been removed.
 * Tests covering EnemyAttackConfig, EnemyObservation, Race, and GameState staging remain.
 */
class EnemyStrategyTest {

    @Test
    void attackConfigHoldsWaveTriggers() {
        EnemyAttackConfig cfg = new EnemyAttackConfig(3, 200, 0, 0);
        assertThat(cfg.armyThreshold()).isEqualTo(3);
        assertThat(cfg.attackIntervalFrames()).isEqualTo(200);
    }

    @Test
    void attackConfigHoldsRetreatThresholds() {
        EnemyAttackConfig cfg = new EnemyAttackConfig(3, 200, 30, 50);
        assertThat(cfg.retreatHealthPercent()).isEqualTo(30);
        assertThat(cfg.retreatArmyPercent()).isEqualTo(50);
    }

    @Test
    void gameStateIncludesEnemyStagingArea() {
        Unit staged = new Unit("s-1", UnitType.ZEALOT, new Point2d(26, 26),
            100, 100, 50, 50, 0, 0);
        GameState state = new GameState(50, 0, 15, 12,
            List.of(), List.of(), List.of(),
            List.of(),           // enemyBuildings
            List.of(staged),     // enemyStagingArea
            List.of(), List.of(), 0L);
        assertThat(state.enemyStagingArea()).hasSize(1);
        assertThat(state.enemyStagingArea().get(0).tag()).isEqualTo("s-1");
    }

    @Test
    void raceEnumHasThreeValues() {
        assertThat(Race.values()).containsExactlyInAnyOrder(Race.PROTOSS, Race.ZERG, Race.TERRAN);
    }

    @Test
    void enemyObservationHoldsFields() {
        EnemyObservation obs = new EnemyObservation(
            List.of(), java.util.Set.of(BuildingType.NEXUS), 150, 42L);
        assertThat(obs.minerals()).isEqualTo(150);
        assertThat(obs.gameFrame()).isEqualTo(42L);
        assertThat(obs.enemyBuildings()).contains(BuildingType.NEXUS);
    }
}
