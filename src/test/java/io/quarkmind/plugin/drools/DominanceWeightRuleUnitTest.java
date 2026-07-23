package io.quarkmind.plugin.drools;

import io.quarkmind.agent.WeightModifier;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.PatternAssessment;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DominanceWeightRuleUnitTest {

    @Inject RuleUnit<DominanceWeightRuleUnit> ruleUnit;

    private List<WeightModifier> fire(DominanceWeightRuleUnit data) {
        try (RuleUnitInstance<DominanceWeightRuleUnit> instance =
                 ruleUnit.createInstance(data)) {
            instance.fire();
        }
        return data.getModifiers();
    }

    @Test
    void noSignals_noModifiers() {
        var data = new DominanceWeightRuleUnit();
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.isEmpty());
    }

    @Test
    void highConfidenceRush_emitsArmyBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_MARINE_RUSH, 0.7, 3000, "test"));
        List<WeightModifier> mods = fire(data);
        assertFalse(mods.isEmpty());
        double totalArmyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(totalArmyDelta > 0, "Army delta should be positive for rush");
        double totalEconomyDelta = mods.stream()
            .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(totalEconomyDelta < 0, "Economy delta should be negative for rush");
    }

    @Test
    void moderateConfidenceRush_emitsSmallerBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.ZERG_ZERGLING_RUSH, 0.4, 2000, "test"));
        List<WeightModifier> mods = fire(data);
        double totalArmyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(totalArmyDelta > 0);
        assertTrue(totalArmyDelta < 0.15,
            "Moderate rush should produce smaller boost than high confidence");
    }

    @Test
    void rushBelowThreshold_noModifier() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_MARINE_RUSH, 0.29, 3000, "test"));
        List<WeightModifier> mods = fire(data);
        boolean hasRushMod = mods.stream()
            .anyMatch(m -> m.reason().toLowerCase().contains("rush"));
        assertFalse(hasRushMod);
    }

    @Test
    void pushArchetype_emitsTechAndArmyBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_BIO_TIMING, 0.6, 5000, "test"));
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        double techDelta = mods.stream()
            .mapToDouble(WeightModifier::techDelta).sum();
        assertTrue(armyDelta > 0);
        assertTrue(techDelta > 0);
    }

    @Test
    void harassArchetype_emitsTechBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_BANSHEE_HARASS, 0.5, 6000, "test"));
        List<WeightModifier> mods = fire(data);
        double techDelta = mods.stream()
            .mapToDouble(WeightModifier::techDelta).sum();
        assertTrue(techDelta > 0);
    }

    @Test
    void macroArchetype_boostsEconomyAndTech() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.ZERG_MACRO, 0.6, 4000, "test"));
        List<WeightModifier> mods = fire(data);
        double economyDelta = mods.stream()
            .mapToDouble(WeightModifier::economyDelta).sum();
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(economyDelta > 0, "Macro should boost economy");
        assertTrue(armyDelta < 0, "Macro should reduce army weight");
    }

    @Test
    void phaseOnly_defensiveHold_boostsArmy() {
        var data = new DominanceWeightRuleUnit();
        data.getPhaseStore().add("DEFENSIVE_HOLD");
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0);
    }

    @Test
    void phaseOnly_earlyMacro_boostsEconomy() {
        var data = new DominanceWeightRuleUnit();
        data.getPhaseStore().add("EARLY_MACRO");
        List<WeightModifier> mods = fire(data);
        double economyDelta = mods.stream()
            .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(economyDelta > 0);
    }

    @Test
    void phaseOnly_midSkirmish_boostsArmy() {
        var data = new DominanceWeightRuleUnit();
        data.getPhaseStore().add("MID_SKIRMISH");
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0);
    }

    @Test
    void phaseOnly_earlyAggression_boostsArmy() {
        var data = new DominanceWeightRuleUnit();
        data.getPhaseStore().add("EARLY_AGGRESSION");
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0);
    }

    @Test
    void combinedSignal_rushAndDefensiveHold_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_MARINE_RUSH, 0.7, 3000, "test"));
        data.getPhaseStore().add("DEFENSIVE_HOLD");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3,
            "Expected rush + phase + combined modifiers, got " + mods.size());
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0.15,
            "Combined army delta should exceed individual rush modifier");
    }

    @Test
    void combinedSignal_macroAndEarlyMacro_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.PROTOSS_MACRO, 0.6, 4000, "test"));
        data.getPhaseStore().add("EARLY_MACRO");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
        double economyDelta = mods.stream()
            .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(economyDelta > 0.10,
            "Combined economy delta should exceed individual macro modifier");
    }

    @Test
    void combinedSignal_pushAndMidSkirmish_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_MECH_PUSH, 0.6, 7000, "test"));
        data.getPhaseStore().add("MID_SKIRMISH");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        double techDelta = mods.stream()
            .mapToDouble(WeightModifier::techDelta).sum();
        assertTrue(armyDelta > 0);
        assertTrue(techDelta > 0);
    }

    @Test
    void combinedSignal_rushAndEarlyAggression_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.ZERG_ZERGLING_RUSH, 0.6, 2000, "test"));
        data.getPhaseStore().add("EARLY_AGGRESSION");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
    }

    @Test
    void multipleArchetypes_allFireAndStack() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_MARINE_RUSH, 0.5, 3000, "test"));
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_BIO_TIMING, 0.5, 3000, "test"));
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 2, "Both archetypes should produce modifiers");
    }

    @Test
    void transitioning_noPhaseModifier() {
        var data = new DominanceWeightRuleUnit();
        data.getPhaseStore().add("TRANSITIONING");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.isEmpty(),
            "TRANSITIONING should produce no modifiers");
    }

    @Test
    void unknownPhase_noPhaseModifier() {
        var data = new DominanceWeightRuleUnit();
        data.getPhaseStore().add("SOME_UNKNOWN_PHASE");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.isEmpty());
    }
}
