package io.quarkmind.plugin.scouting;

import io.casehub.api.context.CaseContext;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CascadingPatternClassifier {

    static final double DISPATCH_THRESHOLD = 0.3;
    static final double DECAY_PER_FRAME    = 0.99948;
    static final double NOISE_FLOOR        = 0.01;

    private final double droolsThreshold;
    private final double onnxThreshold;
    private final EnumMap<StrategyArchetype, Double> cumulativeConfidence =
            new EnumMap<>(StrategyArchetype.class);

    public CascadingPatternClassifier(double droolsThreshold, double onnxThreshold) {
        this.droolsThreshold = droolsThreshold;
        this.onnxThreshold = onnxThreshold;
    }

    public CascadeResult classify(List<EvidenceMarker> evidence,
                                  List<ConfidenceRevision> revisions,
                                  Map<String, float[][]> onnxFeatures,
                                  long frame, long prevFrame,
                                  CaseContext ctx) {
        long framesElapsed = prevFrame >= 0 ? frame - prevFrame : 0;

        mergeCumulative(cumulativeConfidence, computeAllConfidences(evidence), frame, prevFrame);
        applyRevisions(cumulativeConfidence, revisions, framesElapsed);

        double maxConfidence = cumulativeConfidence.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0);

        if (maxConfidence >= droolsThreshold) {
            return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.DROOLS), false);
        }

        return new CascadeResult(allAssessments(cumulativeConfidence, frame, AssessmentSource.DROOLS), false);
    }

    public void reset() {
        cumulativeConfidence.clear();
    }

    private static double computeTickConfidence(List<EvidenceMarker> markers) {
        if (markers.isEmpty()) { return 0.0; }
        double product = 1.0;
        for (EvidenceMarker m : markers) {
            product *= (1.0 - m.weight());
        }
        return 1.0 - product;
    }

    private static Map<StrategyArchetype, Double> computeAllConfidences(List<EvidenceMarker> markers) {
        return markers.stream()
                .collect(Collectors.groupingBy(EvidenceMarker::archetype))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> computeTickConfidence(e.getValue())));
    }

    private static void mergeCumulative(EnumMap<StrategyArchetype, Double> cumulative,
                                        Map<StrategyArchetype, Double> thisTick,
                                        long currentFrame, long lastFrame) {
        if (lastFrame >= 0) {
            long elapsed = currentFrame - lastFrame;
            double decay = Math.pow(DECAY_PER_FRAME, elapsed);
            cumulative.replaceAll((arch, conf) -> conf * decay);
        }
        thisTick.forEach((arch, conf) ->
                cumulative.merge(arch, conf, Math::max));
        cumulative.values().removeIf(v -> v < NOISE_FLOOR);
    }

    private static void applyRevisions(EnumMap<StrategyArchetype, Double> cumulative,
                                       List<ConfidenceRevision> revisions,
                                       long framesElapsed) {
        for (ConfidenceRevision rev : revisions) {
            cumulative.computeIfPresent(rev.archetype(), (arch, conf) ->
                    Math.max(0, conf * Math.pow(rev.dampingFactor(), framesElapsed)));
        }
    }

    private static List<PatternAssessment> allAssessments(
            EnumMap<StrategyArchetype, Double> cumulative, long frame, AssessmentSource source) {
        return cumulative.entrySet().stream()
                .filter(e -> e.getValue() >= DISPATCH_THRESHOLD)
                .sorted(Map.Entry.<StrategyArchetype, Double>comparingByValue().reversed())
                .map(e -> new PatternAssessment(e.getKey(), e.getValue(), frame,
                        e.getKey().name() + " (confidence " +
                                String.format("%.2f", e.getValue()) + ")",
                        source))
                .toList();
    }
}
