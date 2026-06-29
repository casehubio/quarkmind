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
        task.fireRules(100);

        assertThat(receivedMoments).isNotEmpty();
        assertThat(receivedMoments.get(0).payload().type()).isEqualTo(GameMomentType.FIRST_CONTACT);
    }

    @Test
    void detectsNexusUnderAttack_whenTimingAlertAndHighArmySize() {
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.TimingAlert(true), 200, LEVEL_1));
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ArmySize(8), 200, LEVEL_1));
        task.fireRules(200);

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
        task.fireRules(100);

        assertThat(receivedMoments).hasSize(1);
        assertThat(receivedMoments.get(0).payload().type()).isEqualTo(GameMomentType.FIRST_CONTACT);

        // Second tick: same event, should NOT fire again
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ThreatPosition(new Point2d(60, 60)),
            200, LEVEL_1));
        task.fireRules(200);

        // Still only one FIRST_CONTACT
        assertThat(receivedMoments)
            .filteredOn(e -> e.payload().type() == GameMomentType.FIRST_CONTACT)
            .hasSize(1);
    }
}
