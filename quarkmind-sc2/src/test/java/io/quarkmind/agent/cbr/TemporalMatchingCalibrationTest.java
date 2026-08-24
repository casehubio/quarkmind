package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.DtwSimilarity;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.WarpingConstraint;
import io.quarkmind.domain.TimelineObservation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("benchmark")
class TemporalMatchingCalibrationTest {

    @Test
    void selfRetrieval_sameTimelineInTop1() {
        var fastExpand = buildEconomyTimeline(20, 12, 200, 2);
        var rush = buildRushTimeline(20, 8, 50, 15);
        var turtle = buildTurtleTimeline(20, 10, 400, 5);

        var schema = SC2CbrSchemaRegistrar.buildStrategySchema();
        var tsField = (FeatureField.TimeSeries) schema.fields().stream()
                .filter(f -> f.name().equals("timeline")).findFirst().orElseThrow();

        var queryObs = toObservationMaps(fastExpand.subList(0, 6));
        var fastExpandObs = toObservationMaps(fastExpand);
        var rushObs = toObservationMaps(rush);
        var turtleObs = toObservationMaps(turtle);

        var constraint = new WarpingConstraint.SakoeChibaBand(3);
        double scoreFE = DtwSimilarity.compute(queryObs, fastExpandObs, tsField, constraint).score();
        double scoreRush = DtwSimilarity.compute(queryObs, rushObs, tsField, constraint).score();
        double scoreTurtle = DtwSimilarity.compute(queryObs, turtleObs, tsField, constraint).score();

        assertThat(scoreFE).as("fast-expand self-match should be highest")
                .isGreaterThan(scoreRush)
                .isGreaterThan(scoreTurtle);
    }

    @Test
    void dtwLatency_under10ms_perQuery() {
        var schema = SC2CbrSchemaRegistrar.buildStrategySchema();
        var tsField = (FeatureField.TimeSeries) schema.fields().stream()
                .filter(f -> f.name().equals("timeline")).findFirst().orElseThrow();

        var query = toObservationMaps(buildEconomyTimeline(8, 12, 100, 0));
        var caseTimeline = toObservationMaps(buildEconomyTimeline(20, 14, 150, 3));
        var constraint = new WarpingConstraint.SakoeChibaBand(3);

        for (int i = 0; i < 100; i++) {
            DtwSimilarity.compute(query, caseTimeline, tsField, constraint);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            DtwSimilarity.compute(query, caseTimeline, tsField, constraint);
        }
        long elapsed = System.nanoTime() - start;
        double avgMs = elapsed / 1_000_000.0 / 1000;

        System.out.printf("[TEMPORAL-BENCH] DTW avg latency: %.3f ms (1000 iterations, 8x20 observations)%n", avgMs);
        assertThat(avgMs).as("single DTW query should be under 10ms").isLessThan(10.0);
    }

    @Test
    void extractPrediction_producesValidPrediction() {
        var queryTimeline = buildEconomyTimeline(6, 12, 100, 0);

        var caseTimeline = buildEconomyTimeline(20, 12, 100, 0);
        var features = new java.util.HashMap<String, FeatureValue>();
        features.put("timeline", FeatureValue.structList(toObservationMaps(caseTimeline)));
        features.put("phase_sequence", FeatureValue.stringList("EARLY_MACRO", "MID_SKIRMISH"));
        features.put("enemy_archetype", FeatureValue.string("ZERG_ROACH_RUSH"));
        var storedCase = new SC2GameCbrCase("vs ZERG (PvZ)", "strategy.drools", "WIN", 0.9, features);
        var scored = new ScoredCbrCase<>(storedCase, 0.85);

        var task = new TemporalCbrTask(null, null, null);
        var prediction = task.extractPrediction(queryTimeline, List.of(scored));

        assertThat(prediction).isNotNull();
        assertThat(prediction.matchCount()).isEqualTo(1);
        assertThat(prediction.bestMatchScore()).isEqualTo(0.85);
        assertThat(prediction.economyTrend()).isNotNull();
        assertThat(prediction.armyTrend()).isNotNull();
        assertThat(prediction.minutesToNextTransition()).isGreaterThan(0);
    }

    private static List<TimelineObservation> buildEconomyTimeline(int steps, int startWorkers, int startMinerals, int startArmy) {
        var timeline = new ArrayList<TimelineObservation>();
        for (int i = 0; i < steps; i++) {
            timeline.add(new TimelineObservation(
                    i * 0.5, startWorkers + i, startMinerals + i * 50, startArmy + i * 2));
        }
        return timeline;
    }

    private static List<TimelineObservation> buildRushTimeline(int steps, int startWorkers, int startMinerals, int startArmy) {
        var timeline = new ArrayList<TimelineObservation>();
        for (int i = 0; i < steps; i++) {
            timeline.add(new TimelineObservation(
                    i * 0.5, startWorkers, startMinerals + i * 10, startArmy + i * 5));
        }
        return timeline;
    }

    private static List<TimelineObservation> buildTurtleTimeline(int steps, int startWorkers, int startMinerals, int startArmy) {
        var timeline = new ArrayList<TimelineObservation>();
        for (int i = 0; i < steps; i++) {
            timeline.add(new TimelineObservation(
                    i * 0.5, startWorkers + (i / 3), startMinerals + i * 80, startArmy + i));
        }
        return timeline;
    }

    private static List<Map<String, FeatureValue>> toObservationMaps(List<TimelineObservation> timeline) {
        return timeline.stream()
                .map(t -> Map.<String, FeatureValue>of(
                        "minute", FeatureValue.number(t.minute()),
                        "our_workers", FeatureValue.number(t.ourWorkers()),
                        "our_minerals", FeatureValue.number(t.ourMinerals()),
                        "our_army_supply", FeatureValue.number(t.ourArmySupply())))
                .toList();
    }
}
