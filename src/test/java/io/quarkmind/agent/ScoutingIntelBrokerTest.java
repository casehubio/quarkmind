package io.quarkmind.agent;

import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutingIntelBrokerTest {

    // ---- in-memory store: update / current ----

    @Test
    void update_storesLatestByType() {
        var broker = new ScoutingIntelBroker();
        broker.update(new ScoutingIntelPayload.ThreatPosition(new Point2d(10f, 20f)));

        Optional<ScoutingIntelPayload.ThreatPosition> result =
            broker.current(ScoutingIntelType.THREAT_POSITION, ScoutingIntelPayload.ThreatPosition.class);
        assertThat(result).isPresent();
        assertThat(result.get().position()).isEqualTo(new Point2d(10f, 20f));
    }

    @Test
    void current_returnsEmpty_beforeFirstUpdate() {
        var broker = new ScoutingIntelBroker();
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isEmpty();
        assertThat(broker.current(ScoutingIntelType.POSTURE, ScoutingIntelPayload.PostureUpdate.class)).isEmpty();
    }

    @Test
    void update_overwritesPreviousValue() {
        var broker = new ScoutingIntelBroker();
        broker.update(new ScoutingIntelPayload.ArmySize(3));
        broker.update(new ScoutingIntelPayload.ArmySize(7));

        assertThat(broker.current(ScoutingIntelType.ARMY_SIZE, ScoutingIntelPayload.ArmySize.class))
            .isPresent()
            .map(ScoutingIntelPayload.ArmySize::count)
            .hasValue(7);
    }

    @Test
    void update_storesEachTypeIndependently() {
        var broker = new ScoutingIntelBroker();
        broker.update(new ScoutingIntelPayload.ThreatPosition(new Point2d(1f, 1f)));
        broker.update(new ScoutingIntelPayload.PostureUpdate("AGGRESSIVE"));

        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isPresent();
        assertThat(broker.current(ScoutingIntelType.POSTURE)).isPresent();
        assertThat(broker.current(ScoutingIntelType.ARMY_SIZE)).isEmpty();
    }

    @Test
    void onGameStarted_clearsAllLatestValues() {
        var broker = new ScoutingIntelBroker();
        broker.update(new ScoutingIntelPayload.ThreatPosition(new Point2d(5f, 5f)));
        broker.update(new ScoutingIntelPayload.PostureUpdate("ALL_IN"));

        broker.onGameStarted(new GameStarted());

        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isEmpty();
        assertThat(broker.current(ScoutingIntelType.POSTURE)).isEmpty();
    }

    @Test
    void clearLatest_clearsAllStoredValues() {
        var broker = new ScoutingIntelBroker();
        broker.update(new ScoutingIntelPayload.ArmySize(5));
        broker.clearLatest();

        assertThat(broker.current(ScoutingIntelType.ARMY_SIZE)).isEmpty();
    }

    @Test
    void isSubscribed_returnsFalse_beforePostConstruct() {
        // new ScoutingIntelBroker() skips @PostConstruct; activeTypes = Set.of()
        var broker = new ScoutingIntelBroker();
        assertThat(broker.isSubscribed(ScoutingIntelType.THREAT_POSITION)).isFalse();
    }



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
