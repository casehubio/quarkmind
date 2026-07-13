package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.quarkmind.agent.AnchorInterpolatorTest.anchor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class SituationalDominanceWeightStrategyTest {

    private final SituationalDominanceWeightStrategy strategy =
        new SituationalDominanceWeightStrategy(List.of(
            anchor(0, 0.30, 0.35, 0.20, 0.15)));

    @Test
    void id_returnsSituational() {
        assertThat(strategy.id()).isEqualTo("situational");
    }

    @Test
    void nullPhase_returnsTemporalBaseline() {
        DominanceWeights w = strategy.resolve(new WeightContext(5000, null));
        assertThat(w.economy()).isCloseTo(0.30, offset(0.001));
        assertThat(w.army()).isCloseTo(0.35, offset(0.001));
        assertThat(w.tech()).isCloseTo(0.20, offset(0.001));
        assertThat(w.bases()).isCloseTo(0.15, offset(0.001));
    }

    @Test
    void transitioning_noModifier() {
        DominanceWeights w = strategy.resolve(new WeightContext(5000, "TRANSITIONING"));
        assertThat(w.economy()).isCloseTo(0.30, offset(0.001));
        assertThat(w.army()).isCloseTo(0.35, offset(0.001));
    }

    @Test
    void defensiveHold_spikesArmyWeight() {
        DominanceWeights w = strategy.resolve(new WeightContext(5000, "DEFENSIVE_HOLD"));
        assertThat(w.army()).isGreaterThan(0.35);
        assertThat(w.economy()).isLessThan(0.30);
    }

    @Test
    void earlyMacro_boostsEconomyWeight() {
        DominanceWeights w = strategy.resolve(new WeightContext(5000, "EARLY_MACRO"));
        assertThat(w.economy()).isGreaterThan(0.30);
        assertThat(w.army()).isLessThan(0.35);
    }

    @Test
    void earlyAggression_boostsArmyWeight() {
        DominanceWeights w = strategy.resolve(new WeightContext(5000, "EARLY_AGGRESSION"));
        assertThat(w.army()).isGreaterThan(0.35);
    }

    @Test
    void midSkirmish_boostsArmyWeight() {
        DominanceWeights w = strategy.resolve(new WeightContext(5000, "MID_SKIRMISH"));
        assertThat(w.army()).isGreaterThan(0.35);
    }

    @Test
    void allPhases_sumToOne() {
        for (String phase : List.of("DEFENSIVE_HOLD", "EARLY_AGGRESSION", "EARLY_MACRO",
                "MID_SKIRMISH", "TRANSITIONING")) {
            DominanceWeights w = strategy.resolve(new WeightContext(5000, phase));
            double sum = w.economy() + w.army() + w.tech() + w.bases();
            assertThat(sum).as("phase=" + phase).isCloseTo(1.0, offset(0.001));
        }
    }

    @Test
    void allWeights_atLeastFloor() {
        for (String phase : List.of("DEFENSIVE_HOLD", "EARLY_AGGRESSION", "EARLY_MACRO",
                "MID_SKIRMISH", "TRANSITIONING")) {
            DominanceWeights w = strategy.resolve(new WeightContext(5000, phase));
            assertThat(w.economy()).as("economy phase=" + phase).isGreaterThanOrEqualTo(0.05);
            assertThat(w.army()).as("army phase=" + phase).isGreaterThanOrEqualTo(0.05);
            assertThat(w.tech()).as("tech phase=" + phase).isGreaterThanOrEqualTo(0.05);
            assertThat(w.bases()).as("bases phase=" + phase).isGreaterThanOrEqualTo(0.05);
        }
    }

    @Test
    void unknownPhase_treatedAsNoModifier() {
        DominanceWeights w = strategy.resolve(new WeightContext(5000, "UNKNOWN_PHASE"));
        assertThat(w.economy()).isCloseTo(0.30, offset(0.001));
        assertThat(w.army()).isCloseTo(0.35, offset(0.001));
    }

    @Test
    void phaseModifier_composesWithInterpolation() {
        var multiAnchor = new SituationalDominanceWeightStrategy(List.of(
            anchor(0, 0.40, 0.20, 0.25, 0.15),
            anchor(10000, 0.20, 0.40, 0.25, 0.15)));
        DominanceWeights w = multiAnchor.resolve(new WeightContext(5000, "DEFENSIVE_HOLD"));
        assertThat(w.army()).isGreaterThan(0.30);
        double sum = w.economy() + w.army() + w.tech() + w.bases();
        assertThat(sum).isCloseTo(1.0, offset(0.001));
    }
}
