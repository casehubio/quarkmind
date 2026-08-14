package io.quarkmind.agent;

import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutingConvergenceEvaluatorTest {

    @Test
    void exactMatch_returnsOnePointZero() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.ZERG_ROACH_RUSH,
                List.of(new PatternAssessment(StrategyArchetype.ZERG_ROACH_RUSH, 0.8, 5000, "test")));
        assertThat(result.convergence()).isEqualTo(1.0);
        assertThat(result.stable()).isTrue();
    }

    @Test
    void sameCategorySamePhase_returnsHalf() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.ZERG_ZERGLING_RUSH,
                List.of(new PatternAssessment(StrategyArchetype.ZERG_ROACH_RUSH, 0.7, 5000, "test")));
        assertThat(result.convergence()).isEqualTo(0.5);
        assertThat(result.stable()).isTrue();
    }

    @Test
    void sameCategoryCrossPhase_returnsHalf() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.TERRAN_MARINE_TANK,
                List.of(new PatternAssessment(StrategyArchetype.TERRAN_MECH_LATE, 0.6, 10000, "test")));
        assertThat(result.convergence()).isEqualTo(0.5);
        assertThat(result.stable()).isTrue();
    }

    @Test
    void differentCategory_returnsZero() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.ZERG_ZERGLING_RUSH,
                List.of(new PatternAssessment(StrategyArchetype.ZERG_MACRO, 0.8, 5000, "test")));
        assertThat(result.convergence()).isEqualTo(0.0);
        assertThat(result.stable()).isFalse();
    }

    @Test
    void emptyAssessments_returnsZero() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.ZERG_ROACH_RUSH, List.of());
        assertThat(result.convergence()).isEqualTo(0.0);
        assertThat(result.stable()).isFalse();
    }

    @Test
    void crossRaceDifferentCategory_returnsZero() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.TERRAN_MARINE_RUSH,
                List.of(new PatternAssessment(StrategyArchetype.ZERG_BROOD_LORD, 0.7, 10000, "test")));
        assertThat(result.convergence()).isEqualTo(0.0);
        assertThat(result.stable()).isFalse();
    }

    @Test
    void crossRaceSameCategory_returnsZero() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.TERRAN_MARINE_RUSH,
                List.of(new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.7, 5000, "test")));
        assertThat(result.convergence()).isEqualTo(0.0);
        assertThat(result.stable()).isFalse();
    }

    @Test
    void usesFirstAssessment_notSecond() {
        var result = ScoutingConvergenceEvaluator.evaluate(
                StrategyArchetype.ZERG_ROACH_RUSH,
                List.of(
                    new PatternAssessment(StrategyArchetype.ZERG_MACRO, 0.9, 5000, "first"),
                    new PatternAssessment(StrategyArchetype.ZERG_ROACH_RUSH, 0.4, 5000, "second")));
        assertThat(result.convergence()).isEqualTo(0.0);
    }
}
