package io.quarkmind.plugin.summarisation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngagementOutcomeTest {

    @Test
    void won_whenEnemyLostMoreValue() {
        var outcome = EngagementOutcome.of(100, 500, 2, 5, 200, 300);
        assertThat(outcome.outcome()).isEqualTo(EngagementOutcome.Outcome.WON);
        assertThat(outcome.unitTradeRatio()).isEqualTo(1.5);
    }

    @Test
    void lost_whenOwnLostMoreValue() {
        var outcome = EngagementOutcome.of(100, 500, 5, 2, 400, 100);
        assertThat(outcome.outcome()).isEqualTo(EngagementOutcome.Outcome.LOST);
        assertThat(outcome.unitTradeRatio()).isEqualTo(0.25);
    }

    @Test
    void even_whenValueLossWithinMargin() {
        var outcome = EngagementOutcome.of(100, 500, 3, 3, 200, 220);
        assertThat(outcome.outcome()).isEqualTo(EngagementOutcome.Outcome.EVEN);
    }

    @Test
    void won_whenNoOwnLosses() {
        var outcome = EngagementOutcome.of(100, 500, 0, 3, 0, 300);
        assertThat(outcome.outcome()).isEqualTo(EngagementOutcome.Outcome.WON);
        assertThat(outcome.unitTradeRatio()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void even_whenNoLossesOnEitherSide() {
        var outcome = EngagementOutcome.of(100, 500, 0, 0, 0, 0);
        assertThat(outcome.outcome()).isEqualTo(EngagementOutcome.Outcome.EVEN);
        assertThat(outcome.unitTradeRatio()).isEqualTo(0.0);
    }
}
