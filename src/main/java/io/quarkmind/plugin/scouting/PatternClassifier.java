package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.EnemyArchetype;
import io.quarkmind.domain.EnemyPatternAssessment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PatternClassifier {

    static final double DISPATCH_THRESHOLD = 0.3;

    static double computeTickConfidence(List<EvidenceMarker> markers) {
        if (markers.isEmpty()) return 0.0;
        double product = 1.0;
        for (EvidenceMarker m : markers) {
            product *= (1.0 - m.weight());
        }
        return 1.0 - product;
    }

    static Map<EnemyArchetype, Double> computeAllConfidences(List<EvidenceMarker> markers) {
        return markers.stream()
            .collect(Collectors.groupingBy(EvidenceMarker::archetype))
            .entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> computeTickConfidence(e.getValue())));
    }

    static void mergeCumulative(EnumMap<EnemyArchetype, Double> cumulative,
                                Map<EnemyArchetype, Double> thisTick) {
        thisTick.forEach((arch, conf) ->
            cumulative.merge(arch, conf, Math::max));
    }

    static Optional<EnemyPatternAssessment> topAssessment(
            EnumMap<EnemyArchetype, Double> cumulative, long frame) {
        return cumulative.entrySet().stream()
            .filter(e -> e.getValue() >= DISPATCH_THRESHOLD)
            .max(Map.Entry.comparingByValue())
            .map(e -> new EnemyPatternAssessment(e.getKey(), e.getValue(), frame,
                e.getKey().name() + " (confidence " +
                    String.format("%.2f", e.getValue()) + ")"));
    }

    private PatternClassifier() {}
}
