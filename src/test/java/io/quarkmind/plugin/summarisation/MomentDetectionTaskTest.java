package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.domain.Point2d;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MomentDetectionTask — uses CDI-provided Drools RuleUnit.
 */
@QuarkusTest
class MomentDetectionTaskTest {

    private static final EventLevel LEVEL_1 = new EventLevel("intel", 1);

    @Inject RuleUnit<MomentDetectionRuleUnit> ruleUnit;

    private EventStreamBus<ScoutingIntelPayload> level1Bus;
    private EventStreamBus<GameMoment> momentBus;
    private List<LevelEvent<GameMoment>> receivedMoments;
    private MomentDetectionTask task;

    @BeforeEach
    void setUp() {
        level1Bus = new EventStreamBus<>();
        momentBus = new EventStreamBus<>();
        receivedMoments = new ArrayList<>();
        momentBus.subscribe(m -> true, receivedMoments::add);

        task = new MomentDetectionTask(ruleUnit);
        task.setLevel1Bus(level1Bus);
        task.setMomentBus(momentBus);
        task.init();
    }

    @Test
    void detectsFirstContact_whenThreatPositionArrives() {
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ThreatPosition(new Point2d(50, 50)),
            100, LEVEL_1));
        task.fireRules(100, 0, 0);

        assertThat(receivedMoments).isNotEmpty();
        assertThat(receivedMoments.get(0).payload().type()).isEqualTo(GameMomentType.FIRST_CONTACT);
    }

    @Test
    void detectsNexusUnderAttack_whenTimingAlertAndHighArmySize() {
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.TimingAlert(true), 200, LEVEL_1));
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ArmySize(8), 200, LEVEL_1));
        task.fireRules(200, 0, 0);

        assertThat(receivedMoments)
            .extracting(e -> e.payload().type())
            .contains(GameMomentType.NEXUS_UNDER_ATTACK);
    }

    @Test
    void deduplicatesFirstContact_acrossMultipleTicks() {
        // First tick: FIRST_CONTACT fires
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ThreatPosition(new Point2d(50, 50)),
            100, LEVEL_1));
        task.fireRules(100, 0, 0);

        assertThat(receivedMoments).hasSize(1);
        assertThat(receivedMoments.get(0).payload().type()).isEqualTo(GameMomentType.FIRST_CONTACT);

        // Second tick: same event, should NOT fire again
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ThreatPosition(new Point2d(60, 60)),
            200, LEVEL_1));
        task.fireRules(200, 0, 0);

        // Still only one FIRST_CONTACT
        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.FIRST_CONTACT)
            .hasSize(1);
    }

    @Test
    void detectsArmyShift_whenArmySizeChangesBy30Percent() {
        // First tick: baseline army size
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ArmySize(10), 100, LEVEL_1));
        task.fireRules(100, 0, 0);

        // No ARMY_SHIFT on first observation
        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.ARMY_SHIFT)
            .isEmpty();

        // Second tick: army size increases by 40% (10 → 14)
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ArmySize(14), 200, LEVEL_1));
        task.fireRules(200, 0, 0);

        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.ARMY_SHIFT)
            .hasSize(1);

        var moment = receivedMoments.stream()
            .filter(e -> e.payload().type() == GameMomentType.ARMY_SHIFT)
            .findFirst().get().payload();

        assertThat(moment.context()).containsEntry("previousValue", 10);
        assertThat(moment.context()).containsEntry("newValue", 14);
    }

    @Test
    void doesNotDetectArmyShift_whenChangeUnder30Percent() {
        // First tick: baseline
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ArmySize(10), 100, LEVEL_1));
        task.fireRules(100, 0, 0);

        // Second tick: only 20% change (10 → 12)
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ArmySize(12), 200, LEVEL_1));
        task.fireRules(200, 0, 0);

        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.ARMY_SHIFT)
            .isEmpty();
    }

    @Test
    void detectsPostureChange_whenPostureChanges() {
        // First tick: baseline posture
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.PostureUpdate("MACRO"), 100, LEVEL_1));
        task.fireRules(100, 0, 0);

        // No POSTURE_CHANGE on first observation
        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.POSTURE_CHANGE)
            .isEmpty();

        // Second tick: posture changes
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.PostureUpdate("ATTACK"), 200, LEVEL_1));
        task.fireRules(200, 0, 0);

        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.POSTURE_CHANGE)
            .hasSize(1);

        var moment = receivedMoments.stream()
            .filter(e -> e.payload().type() == GameMomentType.POSTURE_CHANGE)
            .findFirst().get().payload();

        assertThat(moment.context()).containsEntry("previousPosture", "MACRO");
        assertThat(moment.context()).containsEntry("newPosture", "ATTACK");
    }

    @Test
    void doesNotDetectPostureChange_whenPostureStaysSame() {
        // First tick: baseline
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.PostureUpdate("MACRO"), 100, LEVEL_1));
        task.fireRules(100, 0, 0);

        // Second tick: same posture
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.PostureUpdate("MACRO"), 200, LEVEL_1));
        task.fireRules(200, 0, 0);

        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.POSTURE_CHANGE)
            .isEmpty();
    }

    @Test
    void detectsSupplyBlock_whenSupplyUsedEqualsSupplyCap() {
        level1Bus.publish(new LevelEvent<>(
                new ScoutingIntelPayload.ArmySize(5), 500, LEVEL_1));
        task.fireRules(500, 46, 46);

        assertThat(receivedMoments)
                .extracting(e -> e.payload().type())
                .contains(GameMomentType.SUPPLY_BLOCK);
    }

    @Test
    void doesNotDetectSupplyBlock_whenNotBlocked() {
        level1Bus.publish(new LevelEvent<>(
                new ScoutingIntelPayload.ArmySize(5), 500, LEVEL_1));
        task.fireRules(500, 30, 46);

        assertThat(receivedMoments)
                .extracting(e -> e.payload().type())
                .doesNotContain(GameMomentType.SUPPLY_BLOCK);
    }

    @Test
    void deduplicatesSupplyBlock_withinCooldownWindow() {
        level1Bus.publish(new LevelEvent<>(
                new ScoutingIntelPayload.ArmySize(5), 500, LEVEL_1));
        task.fireRules(500, 46, 46);

        assertThat(receivedMoments)
                .filteredOn(e -> e.payload().type() == GameMomentType.SUPPLY_BLOCK)
                .hasSize(1);

        level1Bus.publish(new LevelEvent<>(
                new ScoutingIntelPayload.ArmySize(5), 600, LEVEL_1));
        task.fireRules(600, 46, 46);

        assertThat(receivedMoments)
                .filteredOn(e -> e.payload().type() == GameMomentType.SUPPLY_BLOCK)
                .hasSize(1);

        level1Bus.publish(new LevelEvent<>(
                new ScoutingIntelPayload.ArmySize(5), 800, LEVEL_1));
        task.fireRules(800, 46, 46);

        assertThat(receivedMoments)
                .filteredOn(e -> e.payload().type() == GameMomentType.SUPPLY_BLOCK)
                .hasSize(2);
    }
}
