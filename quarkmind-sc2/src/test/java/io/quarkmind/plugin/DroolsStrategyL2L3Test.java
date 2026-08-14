package io.quarkmind.plugin;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.drools.StrategyRuleUnit;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Level 2/3 moment and phase consumption in DroolsStrategyTask.
 *
 * <p>Requires {@code @QuarkusTest} — {@code DataSource.createStore()} is initialised
 * at Quarkus build time (GE-0053).
 */
@QuarkusTest
class DroolsStrategyL2L3Test {

    @Inject RuleUnit<StrategyRuleUnit> ruleUnit;

    @Test
    void nexusUnderAttackMoment_triggersDefendStrategy() {
        StrategyRuleUnit data = new StrategyRuleUnit();

        // Given: NEXUS_UNDER_ATTACK moment
        data.getMomentStore().add(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 500L, Map.of()));
        data.getEnemyPostureStore().add("MACRO");
        data.getTimingStore().add(false);

        // When: rules fire
        fire(data);

        // Then: DEFEND strategy is selected
        assertThat(data.getStrategyDecisions()).contains("DEFEND");
    }

    @Test
    void midSkirmishPhaseWithArmy_triggersAttackStrategy() {
        StrategyRuleUnit data = new StrategyRuleUnit();

        // Given: MID_SKIRMISH phase and 4 Stalkers
        data.getTacticalPostureStore().add(new TacticalPosture("MID_SKIRMISH", 1200L, "Engagement at expansion"));
        data.getEnemyPostureStore().add("UNKNOWN");
        data.getTimingStore().add(false);
        data.getArmy().add(stalker("s1"));
        data.getArmy().add(stalker("s2"));
        data.getArmy().add(stalker("s3"));
        data.getArmy().add(stalker("s4"));

        // When: rules fire
        fire(data);

        // Then: ATTACK strategy is selected
        assertThat(data.getStrategyDecisions()).contains("ATTACK");
    }

    @Test
    void nexusUnderAttackMoment_overridesMidSkirmishAttack() {
        StrategyRuleUnit data = new StrategyRuleUnit();

        // Given: both MID_SKIRMISH phase AND NEXUS_UNDER_ATTACK moment
        data.getTacticalPostureStore().add(new TacticalPosture("MID_SKIRMISH", 1200L, "Engagement at expansion"));
        data.getMomentStore().add(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 1250L, Map.of()));
        data.getEnemyPostureStore().add("UNKNOWN");
        data.getTimingStore().add(false);
        data.getArmy().add(stalker("s1"));
        data.getArmy().add(stalker("s2"));
        data.getArmy().add(stalker("s3"));
        data.getArmy().add(stalker("s4"));

        // When: rules fire
        fire(data);

        // Then: DEFEND appears first (higher salience) — Java findFirst() will pick it
        assertThat(data.getStrategyDecisions()).first().isEqualTo("DEFEND");
    }

    @Test
    void existingPostureRules_stillWorkAfterL2L3Integration() {
        StrategyRuleUnit data = new StrategyRuleUnit();

        // Given: ALL_IN posture (existing L1 rule)
        data.getEnemyPostureStore().add("ALL_IN");
        data.getTimingStore().add(false);

        // When: rules fire
        fire(data);

        // Then: DEFEND is selected (regression test)
        assertThat(data.getStrategyDecisions()).contains("DEFEND");
    }

    @Test
    void rushDetected_highConfidence_triggersDefendStrategy() {
        StrategyRuleUnit data = new StrategyRuleUnit();

        data.getPatternStore().add(new io.quarkmind.domain.PatternAssessment(
                io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.8, 500L,
                "8 Marines before 4min"));
        data.getEnemyPostureStore().add("UNKNOWN");
        data.getTimingStore().add(false);

        fire(data);

        assertThat(data.getStrategyDecisions()).contains("DEFEND");
    }

    @Test
    void rushDetected_lowConfidence_noDefend() {
        StrategyRuleUnit data = new StrategyRuleUnit();

        data.getPatternStore().add(new io.quarkmind.domain.PatternAssessment(
                io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.5, 300L,
                "Low confidence"));
        data.getEnemyPostureStore().add("UNKNOWN");
        data.getTimingStore().add(false);

        fire(data);

        assertThat(data.getStrategyDecisions()).doesNotContain("DEFEND");
    }

    @Test
    void macroArchetype_noDefend() {
        StrategyRuleUnit data = new StrategyRuleUnit();

        data.getPatternStore().add(new io.quarkmind.domain.PatternAssessment(
                io.quarkmind.domain.StrategyArchetype.ZERG_MACRO, 0.9, 500L,
                "Macro build"));
        data.getEnemyPostureStore().add("UNKNOWN");
        data.getTimingStore().add(false);

        fire(data);

        assertThat(data.getStrategyDecisions()).doesNotContain("DEFEND");
    }


    private void fire(StrategyRuleUnit data) {
        try (RuleUnitInstance<StrategyRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }
    }

    private Unit stalker(String tag) {
        return new Unit(tag, UnitType.STALKER, new Point2d(15, 15), 160, 160, 80, 80, 0, 0);
    }
}
