package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.quarkmind.agent.AnchorInterpolatorTest.anchor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class TemporalDominanceWeightStrategyTest {

    @Test
    void id_returnsTemporal() {
        var strategy = new TemporalDominanceWeightStrategy(List.of(
            anchor(0, 0.30, 0.35, 0.20, 0.15)));
        assertThat(strategy.id()).isEqualTo("temporal");
    }

    @Test
    void resolve_delegatesToInterpolator() {
        var strategy = new TemporalDominanceWeightStrategy(List.of(
            anchor(0, 0.40, 0.20, 0.25, 0.15),
            anchor(10000, 0.20, 0.40, 0.25, 0.15)));
        DominanceWeights w = strategy.resolve(new WeightContext(5000, null, List.of()));
        assertThat(w.economy()).isCloseTo(0.30, offset(0.001));
        assertThat(w.army()).isCloseTo(0.30, offset(0.001));
    }

    @Test
    void resolve_ignoresPhase() {
        var strategy = new TemporalDominanceWeightStrategy(List.of(
            anchor(0, 0.40, 0.20, 0.25, 0.15),
            anchor(10000, 0.20, 0.40, 0.25, 0.15)));
        DominanceWeights withPhase = strategy.resolve(new WeightContext(5000, "DEFENSIVE_HOLD", List.of()));
        DominanceWeights noPhase = strategy.resolve(new WeightContext(5000, null, List.of()));
        assertThat(withPhase).isEqualTo(noPhase);
    }
}
