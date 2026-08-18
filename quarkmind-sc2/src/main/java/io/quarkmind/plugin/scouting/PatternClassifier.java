package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.PatternAssessment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class PatternClassifier {

    static final double DISPATCH_THRESHOLD = 0.3;
    static final double DECAY_PER_FRAME    = 0.99948;
    static final double NOISE_FLOOR        = 0.01;

    static double computeTickConfidence(List<EvidenceMarker> markers) {
        if (markers.isEmpty()) {return 0.0;}
        double product = 1.0;
        for (EvidenceMarker m : markers) {
            product *= (1.0 - m.weight());
        }
        return 1.0 - product;
    }

    static Map<StrategyArchetype, Double> computeAllConfidences(List<EvidenceMarker> markers) {
        return markers.stream()
                      .collect(Collectors.groupingBy(EvidenceMarker::archetype))
                      .entrySet().stream()
                      .collect(Collectors.toMap(Map.Entry::getKey,
                                                e -> computeTickConfidence(e.getValue())));
    }

    static void mergeCumulative(EnumMap<StrategyArchetype, Double> cumulative,
                                Map<StrategyArchetype, Double> thisTick,
                                long currentFrame, long lastFrame) {
        if (lastFrame >= 0) {
            long   elapsed = currentFrame - lastFrame;
            double decay   = Math.pow(DECAY_PER_FRAME, elapsed);
            cumulative.replaceAll((arch, conf) -> conf * decay);
        }
        thisTick.forEach((arch, conf) ->
                                 cumulative.merge(arch, conf, Math::max));
        cumulative.values().removeIf(v -> v < NOISE_FLOOR);
    }

    static void applyRevisions(EnumMap<StrategyArchetype, Double> cumulative,
                               List<ConfidenceRevision> revisions,
                               long framesElapsed) {
        for (ConfidenceRevision rev : revisions) {
            cumulative.computeIfPresent(rev.archetype(), (arch, conf) ->
                                                                 Math.max(0, conf * Math.pow(rev.dampingFactor(), framesElapsed)));
        }
    }

    static List<PatternAssessment> allAssessments(
            EnumMap<StrategyArchetype, Double> cumulative, long frame) {
        return cumulative.entrySet().stream()
                         .filter(e -> e.getValue() >= DISPATCH_THRESHOLD)
                         .sorted(Map.Entry.<StrategyArchetype, Double>comparingByValue().reversed())
                         .map(e -> new PatternAssessment(e.getKey(), e.getValue(), frame,
                                                              e.getKey().name() + " (confidence " +
                                                              String.format("%.2f", e.getValue()) + ")",
                                                              AssessmentSource.DROOLS))
                         .toList();
    }

    private PatternClassifier() {}
}
