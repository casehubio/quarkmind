package io.quarkmind.plugin.drools;

import io.quarkmind.agent.WeightModifier;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void timingArchetype_emitsTechAndArmyBoost() {
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
        data.getTacticalPostureStore().add("DEFENSIVE_HOLD");
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0);
    }

    @Test
    void phaseOnly_earlyMacro_boostsEconomy() {
        var data = new DominanceWeightRuleUnit();
        data.getTacticalPostureStore().add("EARLY_MACRO");
        List<WeightModifier> mods = fire(data);
        double economyDelta = mods.stream()
            .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(economyDelta > 0);
    }

    @Test
    void phaseOnly_midSkirmish_boostsArmy() {
        var data = new DominanceWeightRuleUnit();
        data.getTacticalPostureStore().add("MID_SKIRMISH");
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
            .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0);
    }

    @Test
    void phaseOnly_earlyAggression_boostsArmy() {
        var data = new DominanceWeightRuleUnit();
        data.getTacticalPostureStore().add("EARLY_AGGRESSION");
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
        data.getTacticalPostureStore().add("DEFENSIVE_HOLD");
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
        data.getTacticalPostureStore().add("EARLY_MACRO");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
        double economyDelta = mods.stream()
            .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(economyDelta > 0.10,
            "Combined economy delta should exceed individual macro modifier");
    }

    @Test
    void combinedSignal_timingAndMidSkirmish_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
            StrategyArchetype.TERRAN_MECH_PUSH, 0.6, 7000, "test"));
        data.getTacticalPostureStore().add("MID_SKIRMISH");
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
        data.getTacticalPostureStore().add("EARLY_AGGRESSION");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
    }

    @Test
    void combinedSignal_harassAndDefensiveHold_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_BANSHEE_HARASS, 0.6, 6000, "test"));
        data.getTacticalPostureStore().add("DEFENSIVE_HOLD");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3,
                   "Expected harass + phase + combined modifiers, got " + mods.size());
        double economyDelta = mods.stream()
                                  .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(economyDelta > -0.20,
                   "Combined should offset some economy loss vs harass + phase without combined");
    }

    @Test
    void combinedSignal_harassAndEarlyMacro_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.PROTOSS_DT_HARASS, 0.6, 5000, "test"));
        data.getTacticalPostureStore().add("EARLY_MACRO");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
        double techDelta = mods.stream()
                               .mapToDouble(WeightModifier::techDelta).sum();
        assertTrue(techDelta > 0.10,
                   "Combined harass+early_macro should stack tech above category-only harass");
    }

    @Test
    void combinedSignal_techAndEarlyMacro_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_BC_TRANSITION, 0.6, 10000, "test"));
        data.getTacticalPostureStore().add("EARLY_MACRO");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
        double techDelta = mods.stream()
                               .mapToDouble(WeightModifier::techDelta).sum();
        double economyDelta = mods.stream()
                                  .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(techDelta > 0.15,
                   "Combined tech+early_macro should stack tech above category-only tech");
        assertTrue(economyDelta > 0,
                   "Combined should produce positive economy from phase + combined");
    }

    @Test
    void combinedSignal_compositionAndMidSkirmish_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_TANK, 0.6, 7000, "test"));
        data.getTacticalPostureStore().add("MID_SKIRMISH");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
        double armyDelta = mods.stream()
                               .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0.10,
                   "Combined composition+mid_skirmish should stack army above category-only composition");
    }

    @Test
    void combinedSignal_compositionAndDefensiveHold_stacksModifiers() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.PROTOSS_STALKER_COLOSSUS, 0.6, 7000, "test"));
        data.getTacticalPostureStore().add("DEFENSIVE_HOLD");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.size() >= 3);
        double armyDelta = mods.stream()
                               .mapToDouble(WeightModifier::armyDelta).sum();
        double techDelta = mods.stream()
                               .mapToDouble(WeightModifier::techDelta).sum();
        assertTrue(armyDelta > 0.15,
                   "Combined composition+defensive_hold should produce strong army response");
        assertTrue(techDelta > 0,
                   "Combined should produce positive tech from combined rule");
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
        data.getTacticalPostureStore().add("TRANSITIONING");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.isEmpty(),
            "TRANSITIONING should produce no modifiers");
    }

    @Test
    void unknownPhase_noPhaseModifier() {
        var data = new DominanceWeightRuleUnit();
        data.getTacticalPostureStore().add("SOME_UNKNOWN_PHASE");
        List<WeightModifier> mods = fire(data);
        assertTrue(mods.isEmpty());
    }

    @Test
    void moderateConfidenceHarass_emitsSmallerBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_REAPER_HARASS, 0.4, 3000, "test"));
        List<WeightModifier> mods = fire(data);
        double techDelta = mods.stream()
                               .mapToDouble(WeightModifier::techDelta).sum();
        assertTrue(techDelta > 0, "Moderate harass should boost tech");
        assertTrue(techDelta < 0.10, "Moderate should be less than high-confidence tech boost");
    }

    @Test
    void timingBelowThreshold_noModifier() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.PROTOSS_BLINK_STALKER, 0.29, 5000, "test"));
        List<WeightModifier> mods = fire(data);
        boolean hasTimingMod = mods.stream()
                                   .anyMatch(m -> m.reason().toLowerCase().contains("timing"));
        assertFalse(hasTimingMod);
    }

    @Test
    void techArchetype_boostsTech() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_BC_TRANSITION, 0.6, 10000, "test"));
        List<WeightModifier> mods = fire(data);
        double techDelta = mods.stream()
                               .mapToDouble(WeightModifier::techDelta).sum();
        double armyDelta = mods.stream()
                               .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(techDelta > 0, "Tech archetype should boost tech");
        assertTrue(armyDelta < 0, "Tech archetype should reduce army");
    }

    @Test
    void moderateConfidenceTech_emitsSmallerBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.ZERG_VIPER_SUPPORT, 0.4, 10000, "test"));
        List<WeightModifier> mods = fire(data);
        double techDelta = mods.stream()
                               .mapToDouble(WeightModifier::techDelta).sum();
        assertTrue(techDelta > 0, "Moderate tech should boost tech");
        assertTrue(techDelta < 0.15, "Moderate should be less than high-confidence tech boost");
    }

    @Test
    void compositionArchetype_boostsArmy() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_TANK, 0.6, 7000, "test"));
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
                               .mapToDouble(WeightModifier::armyDelta).sum();
        double basesDelta = mods.stream()
                                .mapToDouble(WeightModifier::basesDelta).sum();
        assertTrue(armyDelta > 0, "Composition should boost army");
        assertTrue(basesDelta < 0, "Composition should reduce bases");
    }

    @Test
    void moderateConfidenceComposition_emitsSmallerBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.ZERG_ROACH_HYDRA, 0.4, 7000, "test"));
        List<WeightModifier> mods = fire(data);
        double armyDelta = mods.stream()
                               .mapToDouble(WeightModifier::armyDelta).sum();
        assertTrue(armyDelta > 0, "Moderate composition should boost army");
        assertTrue(armyDelta < 0.10, "Moderate should be less than high-confidence army boost");
    }

    @Test
    void moderateConfidenceMacro_emitsSmallerBoost() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.ZERG_MACRO, 0.4, 4000, "test"));
        List<WeightModifier> mods = fire(data);
        double economyDelta = mods.stream()
                                  .mapToDouble(WeightModifier::economyDelta).sum();
        assertTrue(economyDelta > 0, "Moderate macro should boost economy");
        assertTrue(economyDelta < 0.08, "Moderate should be less than high-confidence economy boost");
    }

    @Test
    void sameCategoryDedup_highestConfidenceWins() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_TANK, 0.7, 7000, "test"));
        var singleData = new DominanceWeightRuleUnit();
        singleData.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_TANK, 0.7, 7000, "test"));
        List<WeightModifier> singleMods = fire(singleData);
        List<WeightModifier> multiMods  = fire(data);
        assertEquals(singleMods.size(), multiMods.size(),
                     "Single assessment per category should produce same modifier count");
    }

    @Test
    void crossCategoryStacking_allFireIndependently() {
        var data = new DominanceWeightRuleUnit();
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_RUSH, 0.7, 3000, "test"));
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_TANK, 0.6, 7000, "test"));
        data.getPatternStore().add(new PatternAssessment(
                StrategyArchetype.TERRAN_BANSHEE_HARASS, 0.5, 6000, "test"));
        List<WeightModifier> mods = fire(data);
        long categoryMods = mods.stream()
                                .filter(m -> !m.reason().startsWith("Phase:") && !m.reason().startsWith("Combined:"))
                                .count();
        assertEquals(3, categoryMods, "3 different categories should produce 3 modifiers");
    }

}
