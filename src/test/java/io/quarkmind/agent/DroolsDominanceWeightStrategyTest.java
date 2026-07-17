package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DroolsDominanceWeightStrategyTest {

    private static final DominanceWeights BASELINE =
        new DominanceWeights(0.30, 0.30, 0.20, 0.20);

    @Test
    void applyModifiers_emptyList_returnsBaseline() {
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of());
        assertEquals(BASELINE, result);
    }

    @Test
    void applyModifiers_singleModifier_adjustsAndNormalises() {
        var mod = new WeightModifier(0.0, +0.10, 0.0, 0.0, "army boost");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertTrue(result.army() > BASELINE.army());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases(), 0.001);
    }

    @Test
    void applyModifiers_multipleModifiers_stackAdditively() {
        var mod1 = new WeightModifier(-0.05, +0.10, 0.0, 0.0, "rush");
        var mod2 = new WeightModifier(-0.05, +0.05, 0.0, 0.0, "phase");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod1, mod2));
        assertTrue(result.army() > result.economy());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases(), 0.001);
    }

    @Test
    void applyModifiers_floorPreventsZero() {
        var mod = new WeightModifier(-0.50, 0.0, 0.0, 0.0, "extreme");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertTrue(result.economy() >= DominanceWeightStrategy.MINIMUM_WEIGHT);
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases(), 0.001);
    }

    @Test
    void applyModifiers_negativeResult_flooredAtMinimum() {
        var mod = new WeightModifier(-1.0, 0.0, 0.0, 0.0, "extreme negative");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertTrue(result.economy() >= DominanceWeightStrategy.MINIMUM_WEIGHT);
    }

    @Test
    void applyModifiers_conflictingModifiers_netEffect() {
        var boost = new WeightModifier(+0.20, 0.0, 0.0, 0.0, "boost");
        var cut = new WeightModifier(-0.10, 0.0, 0.0, 0.0, "cut");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(boost, cut));
        assertTrue(result.economy() > BASELINE.economy());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases(), 0.001);
    }

    @Test
    void applyModifiers_allDimensionsClamped_stillSumToOne() {
        var mod = new WeightModifier(-1.0, -1.0, -1.0, -1.0, "all negative");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertEquals(0.25, result.economy(), 0.001);
        assertEquals(0.25, result.army(), 0.001);
        assertEquals(0.25, result.tech(), 0.001);
        assertEquals(0.25, result.bases(), 0.001);
    }
}
