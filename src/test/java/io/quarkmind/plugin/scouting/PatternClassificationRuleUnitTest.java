package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SignatureSpec;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.scouting.events.EnemyArmyNearBase;
import io.quarkmind.plugin.scouting.events.EnemyExpansionSeen;
import io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PatternClassificationRuleUnitTest {

    @Inject RuleUnit<PatternClassificationRuleUnit> ruleUnit;

    @Test
    void marineRush_highCountEarly_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        for (int i = 0; i < 6; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        }
        data.getGameTimeStore().add(3.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.TERRAN_MARINE_RUSH && e.weight() >= 0.5);
    }

    @Test
    void marineRush_noExpansion_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        data.getGameTimeStore().add(3.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.TERRAN_MARINE_RUSH && e.signal().contains("No expansion"));
    }

    @Test
    void roachRush_earlyRoaches_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        for (int i = 0; i < 5; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ROACH, 120000L));
        }
        data.getGameTimeStore().add(4.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.ZERG_ROACH_RUSH && e.weight() >= 0.5);
    }

    @Test
    void zerglingRush_earlyZerglings_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        for (int i = 0; i < 7; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZERGLING, 90000L));
        }
        data.getGameTimeStore().add(3.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.ZERG_ZERGLING_RUSH && e.weight() >= 0.5);
    }

    @Test
    void gatewayRush_stalkersAndZealotsEarly_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.STALKER, 180000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.STALKER, 180000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZEALOT, 180000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZEALOT, 180000L));
        data.getGameTimeStore().add(4.5);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.PROTOSS_GATEWAY_RUSH);
    }

    @Test
    void mechPush_siegeTanksLate_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.SIEGE_TANK, 300000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.SIEGE_TANK, 300000L));
        data.getGameTimeStore().add(6.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.TERRAN_MECH_PUSH);
    }

    @Test
    void bansheeHarass_bansheeEarly_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.BANSHEE, 300000L));
        data.getGameTimeStore().add(6.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.TERRAN_BANSHEE_HARASS && e.weight() >= 0.6);
    }

    @Test
    void zergMacro_expansionWithFewUnits_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(40, 40), 120000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZERGLING, 120000L));
        data.getGameTimeStore().add(4.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.ZERG_MACRO);
    }

    @Test
    void protossMacro_expansionWithFewUnits_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(40, 40), 120000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZEALOT, 120000L));
        data.getGameTimeStore().add(4.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.PROTOSS_MACRO);
    }

    @Test
    void cannonRush_armyNearBaseEarlyNoStalkers_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.PROBE, 120000L));
        data.getArmyNearBaseEvents().add(new EnemyArmyNearBase(3, 120000L));
        data.getGameTimeStore().add(3.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
                                                        e.archetype() == StrategyArchetype.PROTOSS_CANNON_RUSH);
    }

    @Test
    void cannonRush_doesNotFireForTerranUnitsNearBase() {
        var data = new PatternClassificationRuleUnit();
        // Terran marines near base — should NOT trigger cannon rush
        for (int i = 0; i < 5; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 90000L));
        }
        data.getArmyNearBaseEvents().add(new EnemyArmyNearBase(5, 90000L));
        data.getGameTimeStore().add(2.5);

        fire(data);

        assertThat(data.getEvidence()).noneMatch(e ->
                                                         e.archetype() == StrategyArchetype.PROTOSS_CANNON_RUSH);
    }

    @Test
    void cannonRush_doesNotFireForZergUnitsNearBase() {
        var data = new PatternClassificationRuleUnit();
        for (int i = 0; i < 6; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZERGLING, 90000L));
        }
        data.getArmyNearBaseEvents().add(new EnemyArmyNearBase(6, 90000L));
        data.getGameTimeStore().add(2.5);

        fire(data);

        assertThat(data.getEvidence()).noneMatch(e ->
                                                         e.archetype() == StrategyArchetype.PROTOSS_CANNON_RUSH);
    }

    @Test
    void cannonRush_firesWithProtossUnitsNearBase() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.PROBE, 90000L));
        data.getArmyNearBaseEvents().add(new EnemyArmyNearBase(3, 90000L));
        data.getGameTimeStore().add(2.5);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
                                                        e.archetype() == StrategyArchetype.PROTOSS_CANNON_RUSH);
    }

    @Test
    void zergMacro_doesNotFireForProtossExpansion() {
        var data = new PatternClassificationRuleUnit();
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(40, 40), 120000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZEALOT, 120000L));
        data.getGameTimeStore().add(4.0);

        fire(data);

        assertThat(data.getEvidence()).noneMatch(e ->
                                                         e.archetype() == StrategyArchetype.ZERG_MACRO);
    }

    @Test
    void protossMacro_doesNotFireForZergExpansion() {
        var data = new PatternClassificationRuleUnit();
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(40, 40), 120000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZERGLING, 120000L));
        data.getGameTimeStore().add(4.0);

        fire(data);

        assertThat(data.getEvidence()).noneMatch(e ->
                                                         e.archetype() == StrategyArchetype.PROTOSS_MACRO);
    }

    @Test
    void counterIndication_expansionReducesRushConfidence() {
        var data = new PatternClassificationRuleUnit();
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(40, 40), 120000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZERGLING, 120000L));
        data.getGameTimeStore().add(4.0);

        fire(data);

        assertThat(data.getRevisions()).anyMatch(r ->
                                                         r.archetype() == StrategyArchetype.ZERG_ZERGLING_RUSH && r.dampingFactor() < 1.0);
    }

    @Test
    void counterIndication_techTransitionReducesRushConfidence() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.SIEGE_TANK, 300000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        data.getGameTimeStore().add(6.0);

        fire(data);

        assertThat(data.getRevisions()).anyMatch(r ->
                                                         r.archetype() == StrategyArchetype.TERRAN_MARINE_RUSH && r.dampingFactor() < 1.0);
    }

    @Test
    void counterIndication_predictionWindowExpiry_noAttack() {
        var data = new PatternClassificationRuleUnit();
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        data.getGameTimeStore().add(6.0);

        fire(data);

        assertThat(data.getRevisions()).anyMatch(r ->
                                                         r.archetype() == StrategyArchetype.TERRAN_MARINE_RUSH && r.dampingFactor() < 1.0);
    }

    @Test
    void counterIndication_noRevisions_whenNoCounterEvidence() {
        var data = new PatternClassificationRuleUnit();
        for (int i = 0; i < 6; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        }
        data.getGameTimeStore().add(3.0);

        fire(data);

        assertThat(data.getRevisions().stream()
                       .filter(r -> r.archetype() == StrategyArchetype.TERRAN_MARINE_RUSH))
                .isEmpty();
    }

    @Test
    void counterIndication_expansionOnlyAffectsSameRaceRush() {
        var data = new PatternClassificationRuleUnit();
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(40, 40), 120000L));
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.ZERGLING, 120000L));
        data.getGameTimeStore().add(4.0);

        fire(data);

        assertThat(data.getRevisions()).noneMatch(r ->
                                                          r.archetype().race() == Race.TERRAN);
        assertThat(data.getRevisions()).noneMatch(r ->
                                                          r.archetype().race() == Race.PROTOSS);
    }


    @Test
    void emptyEvents_noEvidence() {
        var data = new PatternClassificationRuleUnit();
        data.getGameTimeStore().add(3.0);

        fire(data);

        assertThat(data.getEvidence()).isEmpty();
    }

    @Test
    void mixedSignals_multipleArchetypesGetEvidence() {
        var data = new PatternClassificationRuleUnit();
        for (int i = 0; i < 6; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        }
        data.getGameTimeStore().add(3.5);

        fire(data);

        long distinctArchetypes = data.getEvidence().stream()
            .map(EvidenceMarker::archetype).distinct().count();
        assertThat(distinctArchetypes).isGreaterThanOrEqualTo(1);
        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.TERRAN_MARINE_RUSH);
    }

    @Test
    void bioTiming_marinesAndMedivacMidGame_producesEvidence() {
        var data = new PatternClassificationRuleUnit();
        for (int i = 0; i < 4; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 240000L));
        }
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MEDIVAC, 300000L));
        data.getGameTimeStore().add(5.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
            e.archetype() == StrategyArchetype.TERRAN_BIO_TIMING && e.weight() >= 0.5);
    }

    @Test
    void generic_unitCountThreshold_emitsEvidence() {
        var data = new PatternClassificationRuleUnit();
        var sig = new SignatureSpec(
                StrategyArchetype.TERRAN_BIO_TIMING, UnitType.MARINE, 3,
                4.0, 10.0, 0.5, false, Race.TERRAN);
        data.getSignatureStore().add(sig);
        for (int i = 0; i < 4; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 300000L));
        }
        data.getGameTimeStore().add(6.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
                                                        e.archetype() == StrategyArchetype.TERRAN_BIO_TIMING
                                                        && e.signal().contains("MARINE")
                                                        && e.signal().contains("in window"));
    }

    @Test
    void generic_noExpansionGate_emitsEvidence() {
        var data = new PatternClassificationRuleUnit();
        var sig = new SignatureSpec(
                StrategyArchetype.TERRAN_MARINE_RUSH, UnitType.MARINE, 1,
                0.0, 5.0, 0.5, true, Race.TERRAN);
        data.getSignatureStore().add(sig);
        data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        data.getGameTimeStore().add(2.0);

        fire(data);

        assertThat(data.getEvidence()).anyMatch(e ->
                                                        e.archetype() == StrategyArchetype.TERRAN_MARINE_RUSH
                                                        && e.signal().contains("No expansion"));
    }

    @Test
    void generic_outsideWindow_noEvidence() {
        var data = new PatternClassificationRuleUnit();
        var sig = new SignatureSpec(
                StrategyArchetype.TERRAN_BIO_TIMING, UnitType.MARINE, 3,
                4.0, 10.0, 0.5, false, Race.TERRAN);
        data.getSignatureStore().add(sig);
        for (int i = 0; i < 5; i++) {
            data.getUnitEvents().add(new EnemyUnitFirstSeen(UnitType.MARINE, 60000L));
        }
        data.getGameTimeStore().add(2.0);

        fire(data);

        assertThat(data.getEvidence()).noneMatch(e ->
                                                         e.signal().contains("in window"));
    }


    private void fire(PatternClassificationRuleUnit data) {
        try (RuleUnitInstance<PatternClassificationRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }
    }
}
