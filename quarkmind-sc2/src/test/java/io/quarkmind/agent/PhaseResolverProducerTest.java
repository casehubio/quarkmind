package io.quarkmind.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseResolverProducerTest {

    private final PhaseResolverProducer producer = new PhaseResolverProducer();

    @Test
    void defaultStrategy_returnsStateBased() {
        var resolver = producer.phaseResolver("state-based");
        assertThat(resolver).isInstanceOf(StateBasedPhaseResolver.class);
    }

    @Test
    void timeBasedStrategy_returnsTimeBased() {
        var resolver = producer.phaseResolver("time-based");
        assertThat(resolver).isInstanceOf(TimeBasedPhaseResolver.class);
    }

    @Test
    void unknownStrategy_defaultsToStateBased() {
        var resolver = producer.phaseResolver("unknown");
        assertThat(resolver).isInstanceOf(StateBasedPhaseResolver.class);
    }
}
