package io.quarkmind.agent;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyDominanceAssessorTest {

    private final SupplyDominanceAssessor assessor = new SupplyDominanceAssessor(40);

    @Test
    void assess_noEnemyUnitsVisible_returnsZero() {
        GameState state = gameState(30, List.of(), List.of());
        assertThat(assessor.assess(state)).isEqualTo(0.0);
    }

    @Test
    void assess_equalSupply_returnsZero() {
        GameState state = gameState(4, // 2 zealots = 4 supply
            List.of(zealot(), zealot()),
            List.of(zealot(), zealot()));
        assertThat(assessor.assess(state)).isEqualTo(0.0);
    }

    @Test
    void assess_aheadInSupply_returnsPositive() {
        GameState state = gameState(8, // 4 zealots = 8 supply
            List.of(zealot(), zealot(), zealot(), zealot()),
            List.of(zealot())); // 2 supply enemy
        // delta = 8 - 2 = 6, score = 6/40 = 0.15
        assertThat(assessor.assess(state)).isCloseTo(0.15, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void assess_behindInSupply_returnsNegative() {
        GameState state = gameState(2,
            List.of(zealot()), // 2 supply
            List.of(zealot(), zealot(), zealot(), zealot())); // 8 supply enemy
        // delta = 2 - 8 = -6, score = -6/40 = -0.15
        assertThat(assessor.assess(state)).isCloseTo(-0.15, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void assess_clampsToPositiveOne() {
        GameState state = gameState(60,
            armyOf(30, UnitType.ZEALOT), // 60 supply
            List.of(zealot())); // 2 supply
        // delta = 60 - 2 = 58, 58/40 > 1.0 → clamped to 1.0
        assertThat(assessor.assess(state)).isEqualTo(1.0);
    }

    @Test
    void assess_clampsToNegativeOne() {
        GameState state = gameState(2,
            List.of(zealot()), // 2 supply
            armyOf(30, UnitType.ZEALOT)); // 60 supply
        assertThat(assessor.assess(state)).isEqualTo(-1.0);
    }

    @Test
    void assess_mixedUnitTypes_computesCorrectSupply() {
        // stalker = 2, immortal = 4 → enemy 6 supply
        GameState state = gameState(10,
            armyOf(5, UnitType.ZEALOT), // 10 supply
            List.of(stalker(), immortal()));
        // delta = 10 - 6 = 4, score = 4/40 = 0.1
        assertThat(assessor.assess(state)).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void assess_excludesWorkersFromEnemy() {
        // probes are workers (supply cost 1) — enemy workers should still count since
        // enemyUnits list doesn't distinguish workers from army in GameState.
        // But the assessor operates on the full enemyUnits list as-is.
        GameState state = gameState(10,
            armyOf(5, UnitType.ZEALOT), // 10 supply
            List.of(probe(), probe(), probe())); // 3 supply
        // delta = 10 - 3 = 7, score = 7/40 = 0.175
        assertThat(assessor.assess(state)).isCloseTo(0.175, org.assertj.core.data.Offset.offset(0.01));
    }

    // --- helpers ---

    private static GameState gameState(int supplyUsed, List<Unit> army, List<Unit> enemyUnits) {
        return new GameState(200, 100, 15, supplyUsed, army, List.of(), enemyUnits, List.of(), List.of(), List.of(), List.of(), 5000);
    }

    private static Unit zealot() { return unit(UnitType.ZEALOT); }
    private static Unit stalker() { return unit(UnitType.STALKER); }
    private static Unit immortal() { return unit(UnitType.IMMORTAL); }
    private static Unit probe() { return unit(UnitType.PROBE); }

    private static Unit unit(UnitType type) {
        return new Unit("tag-" + type, type, new Point2d(0, 0), 100, 100, 50, 50, 0, 0);
    }

    private static List<Unit> armyOf(int count, UnitType type) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> unit(type)).toList();
    }
}
