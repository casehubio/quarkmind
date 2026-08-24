package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.quarkmind.domain.TemporalPrediction.Trend.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TemporalPredictionTest {

    @Test
    void computeTrend_growing() {
        var observations = List.of(
                new TimelineObservation(1.0, 12, 100, 5),
                new TimelineObservation(1.5, 14, 150, 8),
                new TimelineObservation(2.0, 16, 200, 12),
                new TimelineObservation(2.5, 18, 250, 16));
        assertThat(TemporalPrediction.computeEconomyTrend(observations)).isEqualTo(GROWING);
        assertThat(TemporalPrediction.computeArmyTrend(observations)).isEqualTo(GROWING);
    }

    @Test
    void computeTrend_declining() {
        var observations = List.of(
                new TimelineObservation(1.0, 16, 200, 20),
                new TimelineObservation(1.5, 14, 150, 15),
                new TimelineObservation(2.0, 12, 100, 10),
                new TimelineObservation(2.5, 10, 50, 5));
        assertThat(TemporalPrediction.computeEconomyTrend(observations)).isEqualTo(DECLINING);
        assertThat(TemporalPrediction.computeArmyTrend(observations)).isEqualTo(DECLINING);
    }

    @Test
    void computeTrend_stable() {
        var observations = List.of(
                new TimelineObservation(1.0, 16, 200, 20),
                new TimelineObservation(1.5, 16, 205, 20),
                new TimelineObservation(2.0, 16, 198, 21),
                new TimelineObservation(2.5, 16, 202, 20));
        assertThat(TemporalPrediction.computeEconomyTrend(observations)).isEqualTo(STABLE);
        assertThat(TemporalPrediction.computeArmyTrend(observations)).isEqualTo(STABLE);
    }

    @Test
    void computeTrend_spike() {
        var observations = List.of(
                new TimelineObservation(1.0, 16, 200, 10),
                new TimelineObservation(1.5, 16, 200, 10),
                new TimelineObservation(2.0, 16, 200, 40),
                new TimelineObservation(2.5, 16, 200, 42));
        assertThat(TemporalPrediction.computeArmyTrend(observations)).isEqualTo(SPIKE);
    }

    @Test
    void computeTrend_unknown_tooFewObservations() {
        var observations = List.of(
                new TimelineObservation(1.0, 16, 200, 20));
        assertThat(TemporalPrediction.computeEconomyTrend(observations)).isEqualTo(UNKNOWN);
        assertThat(TemporalPrediction.computeArmyTrend(observations)).isEqualTo(UNKNOWN);
    }

    @Test
    void computeConfidence_majorityConsensus() {
        double confidence = TemporalPrediction.computeConfidence(0.8, 3, 5);
        assertThat(confidence).isCloseTo(Math.min(1.0, 0.8 * (3.0 / 5.0) * 1.3), within(0.001));
    }

    @Test
    void computeConfidence_unanimousConsensus() {
        double confidence = TemporalPrediction.computeConfidence(0.9, 5, 5);
        assertThat(confidence).isCloseTo(Math.min(1.0, 0.9 * (5.0 / 5.0) * 1.5), within(0.001));
    }

    @Test
    void computeConfidence_noConsensus() {
        double confidence = TemporalPrediction.computeConfidence(0.8, 2, 5);
        assertThat(confidence).isCloseTo(0.8 * (2.0 / 5.0) * 0.5, within(0.001));
    }

    @Test
    void recordFields() {
        var prediction = new TemporalPrediction(
                "MID_SKIRMISH", GROWING, STABLE, 2.5, 0.85, 4, 0.92);
        assertThat(prediction.predictedNextPhase()).isEqualTo("MID_SKIRMISH");
        assertThat(prediction.economyTrend()).isEqualTo(GROWING);
        assertThat(prediction.armyTrend()).isEqualTo(STABLE);
        assertThat(prediction.minutesToNextTransition()).isEqualTo(2.5);
        assertThat(prediction.confidence()).isEqualTo(0.85);
        assertThat(prediction.matchCount()).isEqualTo(4);
        assertThat(prediction.bestMatchScore()).isEqualTo(0.92);
    }
}
