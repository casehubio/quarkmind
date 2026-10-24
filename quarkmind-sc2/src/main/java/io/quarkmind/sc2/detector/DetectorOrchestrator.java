package io.quarkmind.sc2.detector;

import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.perception.EvidenceFusionEngine;
import io.quarkmind.plugin.scouting.EvidenceMarker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DetectorOrchestrator {

    private final DetectorRegistry registry;
    private final EvidenceFusionEngine engine;
    private final TemporalAssessmentTracker tracker;
    private final Map<String, Double> priors;
    private final Map<String, Map<String, Double>> cpts;
    private final Map<String, StrategyArchetype> labelToArchetype;
    private final Map<StrategyArchetype, String> archetypeToLabel;

    private String[] outcomeNames;
    private boolean initialized;

    public DetectorOrchestrator(DetectorRegistry registry,
                                Map<String, Double> priors,
                                Map<String, Map<String, Double>> cpts,
                                Map<String, StrategyArchetype> labelToArchetype,
                                int windowSize) {
        this.registry = registry;
        this.engine = new EvidenceFusionEngine();
        this.tracker = new TemporalAssessmentTracker(windowSize);
        this.priors = priors;
        this.cpts = cpts;
        this.labelToArchetype = labelToArchetype;
        this.archetypeToLabel = buildReverseMapping(labelToArchetype);
    }

    public void initializeNetwork(List<String> activeDetectorNames) {
        BayesNetwork network = SC2NetworkBuilder.build(priors, activeDetectorNames, cpts);
        engine.initialize(network, "Strategy");
        this.outcomeNames = engine.getOutcomeNames();
        initialized = true;
    }

    public List<PatternAssessment> classify(Map<String, Float> detectorOutputs,
                                            List<EvidenceMarker> markers,
                                            long frame) {
        if (!initialized) {return List.of();}

        Map<String, double[]> evidence = new HashMap<>();

        for (var entry : detectorOutputs.entrySet()) {
            float p = entry.getValue();
            evidence.put(entry.getKey(), new double[]{p, 1.0 - p});
        }

        if (!markers.isEmpty()) {
            evidence.put("Strategy", computeMarkerLikelihood(markers, outcomeNames));
        }

        engine.setAllEvidence(evidence);
        engine.update();
        double[] posterior = engine.marginalize();

        tracker.recordPosterior(posterior);
        double[] smoothed = tracker.getSmoothedPosterior();

        return buildAssessments(smoothed, frame);
    }

    public boolean detectTransition() {
        return tracker.detectTransition();
    }

    public void reset() {
        tracker.reset();
    }

    private double[] computeMarkerLikelihood(List<EvidenceMarker> markers, String[] outcomes) {
        double[] likelihood = new double[outcomes.length];
        Arrays.fill(likelihood, 0.1);

        for (EvidenceMarker marker : markers) {
            String label = archetypeToLabel.get(marker.archetype());
            if (label == null) {
                label = marker.archetype().category().name();
            }
            for (int i = 0; i < outcomes.length; i++) {
                if (outcomes[i].equals(label)) {
                    likelihood[i] = Math.max(likelihood[i], marker.weight());
                }
            }
        }
        return likelihood;
    }

    private List<PatternAssessment> buildAssessments(double[] posterior, long frame) {
        List<PatternAssessment> assessments = new ArrayList<>();
        for (int i = 0; i < outcomeNames.length; i++) {
            StrategyArchetype arch = labelToArchetype.get(outcomeNames[i]);
            if (arch != null && posterior[i] > 0.01) {
                assessments.add(new PatternAssessment(
                    arch, posterior[i], frame,
                    outcomeNames[i] + " (fusion)",
                    AssessmentSource.FUSION));
            }
        }
        assessments.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return assessments;
    }

    private static Map<StrategyArchetype, String> buildReverseMapping(
            Map<String, StrategyArchetype> forward) {
        Map<StrategyArchetype, String> reverse = new HashMap<>();
        forward.forEach((label, arch) -> reverse.put(arch, label));
        return reverse;
    }
}
