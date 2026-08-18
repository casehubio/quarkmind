package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.drools.DominanceWeightRuleUnit;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.quarkmind.agent.AnchorInterpolatorTest.anchor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DroolsDominanceWeightStrategyTest {

    @Inject RuleUnit<DominanceWeightRuleUnit> ruleUnit;

    private static final DominanceWeights BASELINE =
        new DominanceWeights(0.30, 0.30, 0.20, 0.10, 0.10);

    private static final List<MilestoneConfig.Dominance.WeightAnchor> ANCHORS = List.of(
        anchor(0, 0.30, 0.30, 0.20, 0.10, 0.10),
        anchor(20160, 0.25, 0.35, 0.20, 0.10, 0.10)
    );

    private DroolsDominanceWeightStrategy createStrategy() {
        return new DroolsDominanceWeightStrategy(ruleUnit, ANCHORS);
    }

    @Test
    void applyModifiers_emptyList_returnsBaseline() {
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of());
        assertEquals(BASELINE, result);
    }

    @Test
    void applyModifiers_singleModifier_adjustsAndNormalises() {
        var mod = new WeightModifier(0.0, +0.10, 0.0, 0.0, 0.0, "army boost");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertTrue(result.army() > BASELINE.army());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases() + result.mapControl(), 0.001);
    }

    @Test
    void applyModifiers_multipleModifiers_stackAdditively() {
        var mod1 = new WeightModifier(-0.05, +0.10, 0.0, 0.0, 0.0, "rush");
        var mod2 = new WeightModifier(-0.05, +0.05, 0.0, 0.0, 0.0, "phase");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod1, mod2));
        assertTrue(result.army() > result.economy());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases() + result.mapControl(), 0.001);
    }

    @Test
    void applyModifiers_floorPreventsZero() {
        var mod = new WeightModifier(-0.50, 0.0, 0.0, 0.0, 0.0, "extreme");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertTrue(result.economy() >= DominanceWeightStrategy.MINIMUM_WEIGHT);
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases() + result.mapControl(), 0.001);
    }

    @Test
    void applyModifiers_negativeResult_flooredAtMinimum() {
        var mod = new WeightModifier(-1.0, 0.0, 0.0, 0.0, 0.0, "extreme negative");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertTrue(result.economy() >= DominanceWeightStrategy.MINIMUM_WEIGHT);
    }

    @Test
    void applyModifiers_conflictingModifiers_netEffect() {
        var boost = new WeightModifier(+0.20, 0.0, 0.0, 0.0, 0.0, "boost");
        var cut = new WeightModifier(-0.10, 0.0, 0.0, 0.0, 0.0, "cut");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(boost, cut));
        assertTrue(result.economy() > BASELINE.economy());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases() + result.mapControl(), 0.001);
    }

    @Test
    void applyModifiers_allDimensionsClamped_stillSumToOne() {
        var mod = new WeightModifier(-1.0, -1.0, -1.0, -1.0, -1.0, "all negative");
        DominanceWeights result = DroolsDominanceWeightStrategy
            .applyModifiers(BASELINE, List.of(mod));
        assertEquals(0.20, result.economy(), 0.001);
        assertEquals(0.20, result.army(), 0.001);
        assertEquals(0.20, result.tech(), 0.001);
        assertEquals(0.20, result.bases(), 0.001);
        assertEquals(0.20, result.mapControl(), 0.001);
    }

    @Test
    void id_returnsDrools() {
        assertEquals("drools", createStrategy().id());
    }

    @Test
    void resolve_emptyContext_returnsBaseline() {
        var ctx = new WeightContext(5000, null, List.of());
        DominanceWeights result = createStrategy().resolve(ctx);
        assertNotNull(result);
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases() + result.mapControl(), 0.001);
    }

    @Test
    void resolve_withRush_shiftsToArmy() {
        var ctx = new WeightContext(5000, null, List.of(
            new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_RUSH, 0.7, 3000, "test", AssessmentSource.DROOLS)));
        DominanceWeights baseline = new AnchorInterpolator(ANCHORS)
            .interpolate(5000);
        DominanceWeights result = createStrategy().resolve(ctx);
        assertTrue(result.army() > baseline.army());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases() + result.mapControl(), 0.001);
    }

    @Test
    void resolve_withPhaseAndPattern_composesAll() {
        var ctx = new WeightContext(5000, "DEFENSIVE_HOLD", List.of(
            new PatternAssessment(
                StrategyArchetype.ZERG_ZERGLING_RUSH, 0.6, 2000, "test", AssessmentSource.DROOLS)));
        DominanceWeights result = createStrategy().resolve(ctx);
        assertTrue(result.army() > 0.40, "Army should dominate: " + result.army());
        assertEquals(1.0, result.economy() + result.army()
            + result.tech() + result.bases() + result.mapControl(), 0.001);
    }

    @Test
    void resolve_deduplicatesPerCategory() {
        var ctx = new WeightContext(5000, null, List.of(
                new PatternAssessment(StrategyArchetype.TERRAN_MARINE_TANK, 0.7, 5000, "test", AssessmentSource.DROOLS),
                new PatternAssessment(StrategyArchetype.TERRAN_BATTLE_MECH, 0.6, 5000, "test", AssessmentSource.DROOLS),
                new PatternAssessment(StrategyArchetype.ZERG_ROACH_HYDRA, 0.4, 5000, "test", AssessmentSource.DROOLS)));
        var singleCtx = new WeightContext(5000, null, List.of(
                new PatternAssessment(StrategyArchetype.TERRAN_MARINE_TANK, 0.7, 5000, "test", AssessmentSource.DROOLS)));
        DominanceWeights multi  = createStrategy().resolve(ctx);
        DominanceWeights single = createStrategy().resolve(singleCtx);
        assertEquals(single.army(), multi.army(), 0.001,
                     "3 COMPOSITION assessments should dedup to highest-confidence one");
        assertEquals(single.economy(), multi.economy(), 0.001);
    }

    @Test
    void resolve_keepsHighestPerCategoryAcrossCategories() {
        var ctx = new WeightContext(5000, null, List.of(
                new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8, 3000, "test", AssessmentSource.DROOLS),
                new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, 2000, "test", AssessmentSource.DROOLS),
                new PatternAssessment(StrategyArchetype.TERRAN_BIO_TIMING, 0.6, 5000, "test", AssessmentSource.DROOLS)));
        var dedupedCtx = new WeightContext(5000, null, List.of(
                new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8, 3000, "test", AssessmentSource.DROOLS),
                new PatternAssessment(StrategyArchetype.TERRAN_BIO_TIMING, 0.6, 5000, "test", AssessmentSource.DROOLS)));
        DominanceWeights multi   = createStrategy().resolve(ctx);
        DominanceWeights deduped = createStrategy().resolve(dedupedCtx);
        assertEquals(deduped.army(), multi.army(), 0.001,
                     "2 RUSH + 1 TIMING should dedup to 1 RUSH (highest) + 1 TIMING");
    }

}
