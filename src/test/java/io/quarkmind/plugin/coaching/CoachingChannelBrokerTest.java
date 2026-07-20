package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoachingChannelBrokerTest {

    private CoachingChannelBroker broker;

    @BeforeEach
    void setUp() {
        broker = new CoachingChannelBroker();
    }

    @Test
    void onCoachingCompleted_storesCommitment() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        UnitType.STALKER, null, 3, 450);
        var event = new CoachingCompleted("worker-1", "coaching", 100,
                                          advice, CoachingUrgencyTier.STRATEGIC, 500);

        broker.onCoachingCompleted(event);

        assertThat(broker.commitments()).containsKey(CoachingDomain.MILITARY);
        assertThat(broker.commitments().get(CoachingDomain.MILITARY).advice()).isEqualTo(advice);
    }

    @Test
    void onCoachingCompleted_generatesCorrelationId() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        UnitType.STALKER, null, 3, 450);
        var event = new CoachingCompleted("worker-1", "coaching", 100,
                                          advice, CoachingUrgencyTier.STRATEGIC, 500);

        broker.onCoachingCompleted(event);

        assertThat(broker.commitments().get(CoachingDomain.MILITARY).correlationId()).isNotNull();
        assertThat(broker.commitments().get(CoachingDomain.MILITARY).correlationId()).isNotEmpty();
    }

    @Test
    void onCoachingCompleted_dispatchesCommand() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        UnitType.STALKER, null, 3, 450);
        var event = new CoachingCompleted("worker-1", "coaching", 100,
                                          advice, CoachingUrgencyTier.STRATEGIC, 500);

        broker.onCoachingCompleted(event);

        assertThat(broker.dispatchCount()).isEqualTo(1);
    }

    @Test
    void onCoachingCompleted_staleFrame_discarded() {
        var advice1 = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                         UnitType.STALKER, null, 3, 450);
        broker.onCoachingCompleted(new CoachingCompleted("w1", "coaching", 200,
                                                         advice1, CoachingUrgencyTier.STRATEGIC, 500));

        var advice2 = new CoachingAdvice("build zealots", CoachingDomain.MILITARY,
                                         UnitType.ZEALOT, null, 2, 450);
        broker.onCoachingCompleted(new CoachingCompleted("w1", "coaching", 100,
                                                         advice2, CoachingUrgencyTier.STRATEGIC, 500));

        assertThat(broker.dispatchCount()).isEqualTo(1);
        assertThat(broker.commitments().get(CoachingDomain.MILITARY).advice().advice()).isEqualTo("build stalkers");
    }

    @Test
    void frameOrderingGate_differentDomains_independent() {
        var militaryAdvice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                                UnitType.STALKER, null, 3, 450);
        var expandAdvice = new CoachingAdvice("expand", CoachingDomain.EXPAND,
                                              null, null, null, 450);

        broker.onCoachingCompleted(new CoachingCompleted("w1", "coaching", 100,
                                                         militaryAdvice, CoachingUrgencyTier.STRATEGIC, 500));
        broker.onCoachingCompleted(new CoachingCompleted("w1", "coaching", 100,
                                                         expandAdvice, CoachingUrgencyTier.ECONOMIC, 500));

        assertThat(broker.commitments()).hasSize(2);
        assertThat(broker.dispatchCount()).isEqualTo(2);
    }

    @Test
    void supersession_replacesSameDomainCommitment() {
        var advice1 = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                         UnitType.STALKER, null, 3, 450);
        broker.onCoachingCompleted(new CoachingCompleted("w1", "coaching", 100,
                                                         advice1, CoachingUrgencyTier.STRATEGIC, 500));

        var advice2 = new CoachingAdvice("build zealots", CoachingDomain.MILITARY,
                                         UnitType.ZEALOT, null, 4, 450);
        broker.onCoachingCompleted(new CoachingCompleted("w1", "coaching", 200,
                                                         advice2, CoachingUrgencyTier.STRATEGIC, 500));

        assertThat(broker.commitments()).hasSize(1);
        assertThat(broker.commitments().get(CoachingDomain.MILITARY).advice().advice()).isEqualTo("build zealots");
        assertThat(broker.dispatchCount()).isEqualTo(2);
    }

    @Test
    void onGameStarted_clearsState() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        UnitType.STALKER, null, 3, 450);
        broker.onCoachingCompleted(new CoachingCompleted("w1", "coaching", 100,
                                                         advice, CoachingUrgencyTier.STRATEGIC, 500));

        broker.onGameStarted(null);

        assertThat(broker.commitments()).isEmpty();
        assertThat(broker.dispatchCount()).isZero();
    }
}
