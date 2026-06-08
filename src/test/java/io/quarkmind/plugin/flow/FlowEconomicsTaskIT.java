package io.quarkmind.plugin.flow;

import io.casehub.annotation.CaseType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.EconomicsTask;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FlowEconomicsTask as a ScoutingIntelConsumer.
 *
 * <p>Requires {@code @QuarkusTest} — {@code @Channel} injection for MutinyEmitter
 * requires SmallRye Reactive Messaging CDI context.
 */
@QuarkusTest
class FlowEconomicsTaskIT {

    @Inject @CaseType("starcraft-game") EconomicsTask economicsTask;
    @Inject ScoutingIntelBroker broker;

    @BeforeEach @AfterEach
    void clearBroker() { broker.clearLatest(); }

    @Test
    void subscribedIntelTypes_includesARMY_SIZE() {
        // FlowEconomicsTask implements ScoutingIntelConsumer — cast to access subscription
        var consumer = (io.quarkmind.agent.plugin.ScoutingIntelConsumer) economicsTask;
        assertThat(consumer.subscribedIntelTypes())
            .contains(ScoutingIntelType.ARMY_SIZE);
    }

    @Test
    void subscribedIntelTypes_doesNotIncludeCepTypes() {
        var consumer = (io.quarkmind.agent.plugin.ScoutingIntelConsumer) economicsTask;
        assertThat(consumer.subscribedIntelTypes())
            .doesNotContain(
                ScoutingIntelType.POSTURE,
                ScoutingIntelType.TIMING_ALERT,
                ScoutingIntelType.BUILD_ORDER,
                ScoutingIntelType.THREAT_POSITION);
    }

    @Test
    void brokerReceivesArmySizeWhenEconomicsSubscribes() {
        // Verify that broker.activeTypes() includes ARMY_SIZE since economics subscribes
        assertThat(broker.activeTypes()).contains(ScoutingIntelType.ARMY_SIZE);
    }

    @Test
    void brokerArmySizeIsReadable_afterUpdate() {
        // Verify the broker read path that FlowEconomicsTask.execute() uses
        broker.update(new ScoutingIntelPayload.ArmySize(7));
        assertThat(broker.current(ScoutingIntelType.ARMY_SIZE, ScoutingIntelPayload.ArmySize.class))
            .isPresent()
            .map(ScoutingIntelPayload.ArmySize::count)
            .hasValue(7);
    }
}
