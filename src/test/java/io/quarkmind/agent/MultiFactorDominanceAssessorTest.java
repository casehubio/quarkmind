package io.quarkmind.agent;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class MultiFactorDominanceAssessorTest {

    private final MultiFactorDominanceAssessor assessor = new MultiFactorDominanceAssessor(
        0.30, 0.35, 0.20, 0.15,  // weights
        25.0, 3000, 2.0, 3,       // max expected deltas
        3                          // min enemy visibility
    );

    // --- fog-of-war combined threshold ---

    @Test
    void assess_belowVisibilityThreshold_returnsNeutral() {
        GameState state = gameState(200, 100, 15, 10,
            List.of(zealot(), zealot()), List.of(nexus()),
            List.of(zealot()), List.of());  // 1 enemy unit + 0 buildings = 1 < 3
        DominanceScore score = assessor.assess(state);
        assertThat(score.overall()).isEqualTo(0.0);
        assertThat(score.factors().values()).allMatch(v -> v == 0.0);
    }

    @Test
    void assess_atVisibilityThreshold_calculates() {
        // Slight imbalance: own has 3 probes, enemy has 2 probes
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), probe(), zealot()), List.of(nexus()),
            List.of(probe(), probe(), zealot()), List.of(nexus()));  // 3 + 1 = 4 >= 3
        DominanceScore score = assessor.assess(state);
        assertThat(score.overall()).isNotEqualTo(0.0).satisfies(v ->
            assertThat(Math.abs(v)).isLessThanOrEqualTo(1.0));
    }

    // --- per-factor fog guards ---

    @Test
    void assess_noEnemyWorkers_economyZero() {
        // 3 enemy combat units + 1 building → passes combined threshold
        // but no enemy workers → economy guard returns 0.0
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), zealot()), List.of(nexus()),
            List.of(zealot(), zealot(), zealot()), List.of(nexus()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("economy")).isEqualTo(0.0);
        assertThat(score.factors().get("army")).isNotEqualTo(0.0);
    }

    @Test
    void assess_noEnemyUnits_armyAndEconomyZero() {
        // 0 enemy units + 3 buildings → passes combined threshold
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), zealot()), List.of(nexus()),
            List.of(), List.of(nexus(), gateway(), gateway()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("economy")).isEqualTo(0.0);
        assertThat(score.factors().get("army")).isEqualTo(0.0);
        assertThat(score.factors().get("tech")).isNotEqualTo(0.0);
    }

    @Test
    void assess_noEnemyBuildings_techAndBasesZero() {
        // 3 enemy units + 0 buildings → passes combined threshold
        // Own has 3 probes (advantage), enemy has 2 probes
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), probe(), zealot()), List.of(nexus(), gateway()),
            List.of(probe(), probe(), zealot()), List.of());
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("tech")).isEqualTo(0.0);
        assertThat(score.factors().get("bases")).isEqualTo(0.0);
        assertThat(score.factors().get("economy")).isGreaterThan(0.0);
    }

    // --- economy factor ---

    @Test
    void assess_economyAdvantage_positive() {
        // Own: 5 probes. Enemy: 2 probes. Income delta positive.
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), probe(), probe(), probe()), List.of(nexus()),
            List.of(probe(), probe(), zealot()), List.of(nexus()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("economy")).isGreaterThan(0.0);
    }

    @Test
    void assess_equalEconomy_zero() {
        // Own: 3 probes. Enemy: 3 probes. Equal income.
        GameState state = gameState(200, 100, 15, 6,
            List.of(probe(), probe(), probe()), List.of(nexus()),
            List.of(probe(), probe(), probe()), List.of(nexus()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("economy")).isCloseTo(0.0, offset(0.001));
    }

    // --- army value factor ---

    @Test
    void assess_armyAdvantage_positive() {
        // Own: 4 zealots (400 minerals). Enemy: 1 zealot (100 minerals).
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), zealot(), zealot(), zealot(), zealot()), List.of(nexus()),
            List.of(probe(), zealot()), List.of(nexus()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("army")).isGreaterThan(0.0);
    }

    @Test
    void assess_armyExcludesWorkers() {
        // Own: 3 probes only (no army). Enemy: 3 probes only.
        // Army value should be 0 on both sides → delta 0.
        GameState state = gameState(200, 100, 15, 6,
            List.of(probe(), probe(), probe()), List.of(nexus()),
            List.of(probe(), probe(), probe()), List.of(nexus()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("army")).isCloseTo(0.0, offset(0.001));
    }

    // --- tech tier factor ---

    @Test
    void assess_techAdvantage_positive() {
        // Own: NEXUS + GATEWAY + ROBOTICS_FACILITY (T1 + T2 → maxTier=2, breadth=2)
        // Enemy: NEXUS + GATEWAY only (T1 → maxTier=1, breadth=1)
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), probe()), List.of(nexus(), gateway(), roboFacility()),
            List.of(probe(), probe(), probe()), List.of(nexus(), gateway()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("tech")).isGreaterThan(0.0);
    }

    @Test
    void assess_incompleteBuilding_notCounted() {
        // Own: NEXUS + incomplete ROBOTICS_FACILITY
        // Enemy: NEXUS + complete GATEWAY
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), probe()), List.of(nexus(), incompleteBuilding(BuildingType.ROBOTICS_FACILITY)),
            List.of(probe(), probe(), probe()), List.of(nexus(), gateway()));
        DominanceScore score = assessor.assess(state);
        // Own has no complete tech buildings, enemy has GATEWAY (T1)
        assertThat(score.factors().get("tech")).isLessThan(0.0);
    }

    // --- base count factor ---

    @Test
    void assess_baseAdvantage_positive() {
        // Own: 2 nexuses. Enemy: 1 nexus.
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), probe()), List.of(nexus(), nexus()),
            List.of(probe(), probe(), probe()), List.of(nexus()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors().get("bases")).isGreaterThan(0.0);
    }

    // --- overall weighting ---

    @Test
    void assess_overallIsWeightedSum() {
        // Equal everything → all factors ~0 → overall ~0
        GameState state = gameState(200, 100, 15, 6,
            List.of(probe(), probe(), probe()), List.of(nexus(), gateway()),
            List.of(probe(), probe(), probe()), List.of(nexus(), gateway()));
        DominanceScore score = assessor.assess(state);
        double expectedOverall = score.factors().get("economy") * 0.30
            + score.factors().get("army") * 0.35
            + score.factors().get("tech") * 0.20
            + score.factors().get("bases") * 0.15;
        assertThat(score.overall()).isCloseTo(
            Math.max(-1.0, Math.min(1.0, expectedOverall)), offset(0.001));
    }

    @Test
    void assess_overallClampedToOne() {
        // Extreme advantage in all factors
        MultiFactorDominanceAssessor smallDelta = new MultiFactorDominanceAssessor(
            0.30, 0.35, 0.20, 0.15, 1.0, 100, 0.5, 1, 3);
        GameState state = gameState(200, 100, 15, 20,
            armyOf(10, UnitType.ZEALOT), List.of(nexus(), nexus(), nexus(), gateway(), roboFacility(), fleetBeacon()),
            List.of(probe()), List.of(nexus()));
        DominanceScore score = smallDelta.assess(state);
        assertThat(score.overall()).isLessThanOrEqualTo(1.0);
        assertThat(score.overall()).isGreaterThanOrEqualTo(-1.0);
    }

    // --- factor keys ---

    @Test
    void assess_containsAllFourFactors() {
        GameState state = gameState(200, 100, 15, 10,
            List.of(probe(), probe(), probe()), List.of(nexus()),
            List.of(probe(), probe(), probe()), List.of(nexus()));
        DominanceScore score = assessor.assess(state);
        assertThat(score.factors()).containsOnlyKeys("economy", "army", "tech", "bases");
    }

    // --- helpers ---

    private static GameState gameState(int minerals, int vespene, int supply, int supplyUsed,
            List<Unit> myUnits, List<Building> myBuildings,
            List<Unit> enemyUnits, List<Building> enemyBuildings) {
        return new GameState(minerals, vespene, supply, supplyUsed,
            myUnits, myBuildings, enemyUnits, enemyBuildings,
            List.of(), List.of(), List.of(), 5000);
    }

    private static Unit probe() { return unit(UnitType.PROBE); }
    private static Unit zealot() { return unit(UnitType.ZEALOT); }

    private static Unit unit(UnitType type) {
        return new Unit("tag-" + type, type, new Point2d(0, 0), 100, 100, 50, 50, 0, 0);
    }

    private static List<Unit> armyOf(int count, UnitType type) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> unit(type)).toList();
    }

    private static Building nexus() { return building(BuildingType.NEXUS); }
    private static Building gateway() { return building(BuildingType.GATEWAY); }
    private static Building roboFacility() { return building(BuildingType.ROBOTICS_FACILITY); }
    private static Building fleetBeacon() { return building(BuildingType.FLEET_BEACON); }

    private static Building building(BuildingType type) {
        return new Building("tag-" + type, type, new Point2d(0, 0), 1000, 1000, true);
    }

    private static Building incompleteBuilding(BuildingType type) {
        return new Building("tag-" + type, type, new Point2d(0, 0), 500, 1000, false);
    }
}
