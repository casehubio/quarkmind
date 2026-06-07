package io.quarkmind.agent;

import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutingIntelBrokerTest {

    @Test
    void computeActiveTypes_singleConsumer_returnsItsTypes() {
        ScoutingIntelConsumer c = () -> Set.of(ScoutingIntelType.THREAT_POSITION, ScoutingIntelType.POSTURE);
        Set<ScoutingIntelType> result = ScoutingIntelBroker.computeActiveTypes(List.of(c));
        assertThat(result).containsExactlyInAnyOrder(
            ScoutingIntelType.THREAT_POSITION, ScoutingIntelType.POSTURE);
    }

    @Test
    void computeActiveTypes_multipleConsumers_returnsUnion() {
        ScoutingIntelConsumer c1 = () -> Set.of(ScoutingIntelType.THREAT_POSITION);
        ScoutingIntelConsumer c2 = () -> Set.of(ScoutingIntelType.ARMY_SIZE, ScoutingIntelType.POSTURE);
        Set<ScoutingIntelType> result = ScoutingIntelBroker.computeActiveTypes(List.of(c1, c2));
        assertThat(result).containsExactlyInAnyOrder(
            ScoutingIntelType.THREAT_POSITION,
            ScoutingIntelType.ARMY_SIZE,
            ScoutingIntelType.POSTURE);
    }

    @Test
    void computeActiveTypes_noConsumers_returnsEmpty() {
        assertThat(ScoutingIntelBroker.computeActiveTypes(List.of())).isEmpty();
    }

    @Test
    void computeActiveTypes_overlappingSubscriptions_deduplicates() {
        ScoutingIntelConsumer c1 = () -> Set.of(ScoutingIntelType.POSTURE);
        ScoutingIntelConsumer c2 = () -> Set.of(ScoutingIntelType.POSTURE);
        Set<ScoutingIntelType> result = ScoutingIntelBroker.computeActiveTypes(List.of(c1, c2));
        assertThat(result).hasSize(1).contains(ScoutingIntelType.POSTURE);
    }
}
