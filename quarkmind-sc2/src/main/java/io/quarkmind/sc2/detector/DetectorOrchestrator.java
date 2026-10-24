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
    private final double                         hardEvidenceThreshold;
    private final double                         sensitivityThreshold;
    private       Map<String, Float>             previousDetectorOutputs = new HashMap<>();


    private String[] outcomeNames;
    private boolean initialized;
    private List<String> activeDetectorNames = List.of();


    public DetectorOrchestrator(DetectorRegistry registry,
                                Map<String, Double> priors,
                                Map<String, Map<String, Double>> cpts,
                                Map<String, StrategyArchetype> labelToArchetype,
                                int windowSize) {this(registry, priors, cpts, labelToArchetype, windowSize, 0.95, 0.01);}

    public DetectorOrchestrator(DetectorRegistry registry,
                                Map<String, Double> priors,
                                Map<String, Map<String, Double>> cpts,
                                Map<String, StrategyArchetype> labelToArchetype,
                                int windowSize,
                                double hardEvidenceThreshold) {this(registry, priors, cpts, labelToArchetype, windowSize, hardEvidenceThreshold, 0.01);}

    public DetectorOrchestrator(DetectorRegistry registry,
                                Map<String, Double> priors,
                                Map<String, Map<String, Double>> cpts,
                                Map<String, StrategyArchetype> labelToArchetype,
                                int windowSize,
                                double hardEvidenceThreshold,
                                double sensitivityThreshold) {
        this.registry              = registry;
        this.engine                = new EvidenceFusionEngine();
        this.tracker               = new TemporalAssessmentTracker(windowSize);
        this.priors                = priors;
        this.cpts                  = cpts;
        this.labelToArchetype      = labelToArchetype;
        this.archetypeToLabel      = buildReverseMapping(labelToArchetype);
        this.hardEvidenceThreshold = hardEvidenceThreshold;
        this.sensitivityThreshold  = sensitivityThreshold;
    }


    public void initializeNetwork(List<String> newDetectorNames) {
        var currentSorted = this.activeDetectorNames.stream().sorted().toList();
        var newSorted     = newDetectorNames.stream().sorted().toList();
        if (initialized && currentSorted.equals(newSorted)) {return;}

        BayesNetwork network = SC2NetworkBuilder.build(priors, newDetectorNames, cpts);
        engine.initialize(network, "Strategy");
        this.outcomeNames        = engine.getOutcomeNames();
        this.activeDetectorNames = List.copyOf(newDetectorNames);
        previousDetectorOutputs.clear();
        initialized = true;
    }

    public java.util.Set<String> getDetectorsToEvaluate(java.util.Set<String> available) {
        if (!initialized || previousDetectorOutputs.isEmpty()) {
            return available;
        }
        Map<String, Double>   sensitivities = engine.getVariableSensitivities();
        java.util.Set<String> toEvaluate    = new java.util.HashSet<>();
        for (String detector : available) {
            double sensitivity = sensitivities.getOrDefault(detector, Double.MAX_VALUE);
            if (sensitivity >= sensitivityThreshold) {
                toEvaluate.add(detector);
            }
        }
        if (toEvaluate.isEmpty()) {
            return available;
        }
        return toEvaluate;
    }


    public List<PatternAssessment> classify(Map<String, Float> detectorOutputs,
                                            List<EvidenceMarker> markers,
                                            long frame) {
        if (!initialized) {return List.of();}

        Map<String, Float> combined = new HashMap<>(previousDetectorOutputs);
        combined.putAll(detectorOutputs);
        previousDetectorOutputs = new HashMap<>(combined);

        Map<String, double[]> evidence = new HashMap<>();

        for (var entry : combined.entrySet()) {
            float p = entry.getValue();
            if (p >= hardEvidenceThreshold) {
                evidence.put(entry.getKey(), new double[]{1.0, 0.0});
            } else if (p <= 1.0 - hardEvidenceThreshold) {
                evidence.put(entry.getKey(), new double[]{0.0, 1.0});
            } else {
                evidence.put(entry.getKey(), new double[]{p, 1.0 - p});
            }
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

    public Map<String, Double> getSensitivities() {
        if (!initialized) {return Map.of();}
        return engine.getVariableSensitivities();
    }

    public io.quarkmind.inference.bayes.BayesStats getStats() {
        if (!initialized) {return null;}
        return engine.getStats();
    }


    public void reset() {
        tracker.reset();
        previousDetectorOutputs.clear();
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
