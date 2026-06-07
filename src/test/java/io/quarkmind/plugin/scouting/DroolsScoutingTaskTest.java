package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DroolsScoutingTask pure-logic methods.
 * Same package as production class to access package-private static helpers.
 */
class DroolsScoutingTaskTest {

    // ---- estimatedEnemyBase: SC2 map (256x256) ----

    @Test
    void estimatedEnemyBase_sc2Map_lowerLeftBase_returnsUpperRightCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 256))
                .isEqualTo(new Point2d(224, 224));
    }

    @Test
    void estimatedEnemyBase_sc2Map_midBase_returnsUpperRightCorner() {
        // Threshold is mapWidth/4 = 64; base at (50,50) is in lower-left zone
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(50, 50), 256))
                .isEqualTo(new Point2d(224, 224));
    }

    @Test
    void estimatedEnemyBase_sc2Map_upperRightBase_returnsLowerLeftCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(200, 200), 256))
                .isEqualTo(new Point2d(32, 32));
    }

    @Test
    void estimatedEnemyBase_sc2Map_aboveThresholdBase_returnsLowerLeftCorner() {
        // Equivalent to BasicScoutingTask's (100,100) → (32,32) case; threshold is 64
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(100, 100), 256))
                .isEqualTo(new Point2d(32, 32));
    }

    // ---- estimatedEnemyBase: emulated map (64x64) ----

    @Test
    void estimatedEnemyBase_emulatedMap_lowerLeftBase_returnsUpperRightCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64))
                .isEqualTo(new Point2d(56, 56));
    }

    @Test
    void estimatedEnemyBase_emulatedMap_result_isWithinMapBounds() {
        // The bug: old code returned (224,224) which the engine clamped to (63,63)
        Point2d result = DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64);
        assertThat(result.x()).isLessThan(64).isGreaterThan(0);
        assertThat(result.y()).isLessThan(64).isGreaterThan(0);
    }

    @Test
    void estimatedEnemyBase_emulatedMap_result_isNotOldClampedValue() {
        // Explicit regression against the old wrong value
        Point2d result = DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64);
        assertThat(result).isNotEqualTo(new Point2d(63, 63));
        assertThat(result).isNotEqualTo(new Point2d(224, 224));
    }

    // ---- shouldDispatchThreatPosition ----

    @Test
    void shouldDispatchThreatPosition_newPosition_exceedsZeroThreshold() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(10.1f, 10f);
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 0.0)).isTrue();
    }

    @Test
    void shouldDispatchThreatPosition_samePosition_returnsFalse() {
        Point2d pos = new Point2d(10f, 10f);
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(pos, pos, 0.0)).isFalse();
    }

    @Test
    void shouldDispatchThreatPosition_movesBelowThreshold_returnsFalse() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(10.5f, 10f); // distance 0.5
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 1.0)).isFalse();
    }

    @Test
    void shouldDispatchThreatPosition_movesAboveThreshold_returnsTrue() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(12f, 10f); // distance 2.0
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 1.0)).isTrue();
    }

    @Test
    void shouldDispatchThreatPosition_firstSighting_prevNull_returnsTrue() {
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(null, new Point2d(5f, 5f), 0.0))
            .isTrue();
    }

    // ---- shouldDispatchArmySize ----

    @Test
    void shouldDispatchArmySize_deltaExceedsThreshold_returnsTrue() {
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 10, 1)).isTrue();
    }

    @Test
    void shouldDispatchArmySize_deltaBelowThreshold_returnsFalse() {
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 5, 1)).isFalse();
    }

    @Test
    void shouldDispatchArmySize_deltaEqualsThreshold_returnsTrue() {
        // >= semantics: delta of exactly 1 with minDelta=1 should dispatch
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 6, 1)).isTrue();
    }
}
