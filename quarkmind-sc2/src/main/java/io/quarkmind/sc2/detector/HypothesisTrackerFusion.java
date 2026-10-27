package io.quarkmind.sc2.detector;

import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.PhaseResolver;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.ScoutingObservation;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesStats;
import io.quarkmind.inference.bayes.HypothesisTracker;
import io.quarkmind.inference.bayes.TrackerSnapshot;
import io.quarkmind.plugin.scouting.EvidenceMarker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HypothesisTrackerFusion {

    private final DetectorRegistry registry;
    private final Map<String, Double> learnedPriors;
    private final Map<String, Map<String, Double>> detectorCpts;
    private final HypothesisPriorLoader.ObservationCpts observationCpts;
    private final Map<String, BayesNetwork> learnedNetworks;
    private final PhaseResolver phaseResolver;

    private HypothesisTracker opponentTracker;
    private String[] archetypes;
    private Map<String, StrategyArchetype> labelToArchetype;
    private boolean initialized;
    private GamePhase currentPhase;
    private Race currentEnemyRace;
    private List<String> currentDetectorNames;

    public HypothesisTrackerFusion(
            DetectorRegistry registry,
            Map<String, Double> learnedPriors,
            Map<String, Map<String, Double>> detectorCpts,
            HypothesisPriorLoader.ObservationCpts observationCpts,
            Map<String, BayesNetwork> learnedNetworks,
            PhaseResolver phaseResolver) {
        this.registry = registry;
        this.learnedPriors = learnedPriors;
        this.detectorCpts = detectorCpts;
        this.observationCpts = observationCpts;
        this.learnedNetworks = learnedNetworks != null ? learnedNetworks : Map.of();
        this.phaseResolver = phaseResolver;
    }

    public void initialize(List<String> detectorNames, GamePhase phase, Race enemyRace) {
        List<String> sorted = detectorNames.stream().sorted().toList();
        if (initialized && currentPhase == phase
                && currentEnemyRace == enemyRace
                && sorted.equals(currentDetectorNames)) {
            return;
        }

        TrackerSnapshot terminal = null;
        if (initialized && opponentTracker != null) {
            terminal = opponentTracker.snapshot();
        }

        Map<String, Double> priors;
        HypothesisPriorLoader.ObservationCpts obsCpts;
        try {
            priors = HypothesisPriorLoader.loadOpponentPriors(phase, enemyRace);
            obsCpts = HypothesisPriorLoader.loadObservationCpts(phase, enemyRace);
        } catch (IllegalArgumentException e) {
            priors = this.learnedPriors;
            obsCpts = this.observationCpts;
        }

        if (terminal != null) {
            priors = seedPriors(priors, terminal);
        }

        opponentTracker = SC2HypothesisNetworkBuilder.buildOpponentTracker(
            enemyRace, phase, priors, sorted, detectorCpts, obsCpts);

        archetypes = SC2HypothesisNetworkBuilder.filteredArchetypes(
            enemyRace, phase, priors);
        labelToArchetype = buildLabelMapping(archetypes, enemyRace);

        currentPhase = phase;
        currentEnemyRace = enemyRace;
        currentDetectorNames = sorted;
        initialized = true;
    }

    public void observeScouting(ScoutingObservation observation) {
        if (opponentTracker == null) return;
        opponentTracker.observeHard("intel", observation.variable(), observation.state());
    }

    public List<PatternAssessment> classify(
            Map<String, Float> detectorOutputs,
            List<EvidenceMarker> markers,
            long frame) {
        if (opponentTracker == null) return List.of();

        for (var entry : detectorOutputs.entrySet()) {
            double p = Math.max(0.01, Math.min(0.99, entry.getValue()));
            opponentTracker.setPerTickEvidence("intel", entry.getKey(),
                new double[]{p, 1.0 - p});
        }

        if (!markers.isEmpty()) {
            double[] likelihood = new double[archetypes.length];
            Arrays.fill(likelihood, 0.1);
            for (EvidenceMarker marker : markers) {
                String label = marker.archetype().name();
                int idx = archetypeIndex(label);
                if (idx >= 0) {
                    likelihood[idx] = Math.max(likelihood[idx], marker.weight());
                }
            }
            opponentTracker.setPerTickEvidence("intel", "drools_evidence", likelihood);
        } else {
            opponentTracker.clearPerTickEvidence("intel", "drools_evidence");
        }

        Map<String, Double> adjustedWeights = new LinkedHashMap<>();
        for (String hypothesis : opponentTracker.viable()) {
            double ptLikelihood = 1.0;
            for (var entry : detectorOutputs.entrySet()) {
                double p = Math.max(0.01, Math.min(0.99, entry.getValue()));
                double[] dist = {p, 1.0 - p};
                double[] marginal = opponentTracker.marginalize(hypothesis, "intel", entry.getKey());
                ptLikelihood *= dot(dist, marginal);
            }
            if (!markers.isEmpty()) {
                double[] droolsDist = computeDroolsLikelihood(markers);
                double[] marginal = opponentTracker.marginalize(hypothesis, "intel", "drools_evidence");
                ptLikelihood *= dot(droolsDist, marginal);
            }
            adjustedWeights.put(hypothesis, opponentTracker.weights().get(hypothesis) * ptLikelihood);
        }
        normalize(adjustedWeights);

        return buildAssessments(adjustedWeights, frame);
    }

    public StrategyArchetype getMapStrategy() {
        if (opponentTracker == null) return null;
        String leader = opponentTracker.leader();
        return leader != null ? labelToArchetype.get(leader) : null;
    }

    public BayesStats getStats() {
        return null;
    }

    public Map<String, Double> getSensitivities() {
        return Map.of();
    }

    public Map<String, Object> getMapAssignment() {
        if (opponentTracker == null) return Map.of();
        return Map.of("Strategy", opponentTracker.leader() != null
            ? opponentTracker.leader() : "unknown");
    }

    public String getExplanationSummary() {
        if (opponentTracker == null) return "Not initialized";
        TrackerSnapshot snap = opponentTracker.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("Viable: ").append(snap.viable().size());
        sb.append(", Leader: ").append(snap.leader());
        sb.append(String.format(", Entropy: %.3f", snap.entropy()));
        int shown = 0;
        for (var entry : snap.weights().entrySet()) {
            if (shown >= 5) break;
            if (snap.viable().contains(entry.getKey())) {
                sb.append(String.format("\n  %s: %.4f", entry.getKey(), entry.getValue()));
                shown++;
            }
        }
        return sb.toString();
    }

    public Set<String> getDetectorsToEvaluate(Set<String> available) {
        if (opponentTracker == null) return available;
        List<String> viable = opponentTracker.viable();
        if (viable.size() <= 1) return Set.of();
        Set<String> needed = new HashSet<>();
        for (String det : available) {
            if (distinguishesViableHypotheses(det, viable)) {
                needed.add(det);
            }
        }
        return needed.isEmpty() ? available : needed;
    }

    public Map<String, Double> opponentWeights() {
        return opponentTracker != null ? opponentTracker.weights() : Map.of();
    }

    public List<String> viableOpponentHypotheses() {
        return opponentTracker != null ? opponentTracker.viable() : List.of();
    }

    public String opponentLeader() {
        return opponentTracker != null ? opponentTracker.leader() : null;
    }

    public boolean isOpponentResolved() {
        return opponentTracker != null && opponentTracker.isResolved();
    }

    public void reset() {
        if (opponentTracker != null) opponentTracker.reset();
        initialized = false;
    }

    private boolean distinguishesViableHypotheses(String detName, List<String> viable) {
        Map<String, Double> detCpt = detectorCpts.get(detName);
        if (detCpt == null) return true;
        double minP = Double.MAX_VALUE, maxP = -Double.MAX_VALUE;
        for (String archetype : viable) {
            double pHigh = detCpt.getOrDefault(archetype, 0.5);
            minP = Math.min(minP, pHigh);
            maxP = Math.max(maxP, pHigh);
        }
        return (maxP - minP) >= 0.1;
    }

    private int archetypeIndex(String label) {
        for (int i = 0; i < archetypes.length; i++) {
            if (archetypes[i].equals(label)) return i;
        }
        return -1;
    }

    private static Map<String, StrategyArchetype> buildLabelMapping(String[] labels, Race race) {
        Map<String, StrategyArchetype> map    = new LinkedHashMap<>();
        String                         prefix = race.name() + "_";
        for (String label : labels) {
            try {
                map.put(label, StrategyArchetype.valueOf(label));
            } catch (IllegalArgumentException e1) {
                try {
                    map.put(label, StrategyArchetype.valueOf(prefix + label));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return map;
    }


    private double[] computeDroolsLikelihood(List<EvidenceMarker> markers) {
        double[] likelihood = new double[archetypes.length];
        Arrays.fill(likelihood, 0.1);
        for (EvidenceMarker marker : markers) {
            int idx = archetypeIndex(marker.archetype().name());
            if (idx >= 0) {
                likelihood[idx] = Math.max(likelihood[idx], marker.weight());
            }
        }
        return likelihood;
    }

    private List<PatternAssessment> buildAssessments(
            Map<String, Double> weights, long frame) {
        List<PatternAssessment> result = new ArrayList<>();
        for (var entry : weights.entrySet()) {
            if (entry.getValue() < 0.01) continue;
            StrategyArchetype archetype = labelToArchetype.get(entry.getKey());
            if (archetype == null) continue;
            result.add(new PatternAssessment(
                archetype, entry.getValue(), frame,
                "hypothesis:" + entry.getKey(), AssessmentSource.FUSION));
        }
        result.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return result;
    }

    private Map<String, Double> seedPriors(
            Map<String, Double> learnedPriors, TrackerSnapshot terminal) {
        Map<String, Double> seeded = new LinkedHashMap<>(learnedPriors);
        for (var entry : terminal.weights().entrySet()) {
            if (terminal.viable().contains(entry.getKey())
                    && seeded.containsKey(entry.getKey())) {
                seeded.put(entry.getKey(), entry.getValue());
            }
        }
        return seeded;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) sum += a[i] * b[i];
        return sum;
    }

    private static void normalize(Map<String, Double> weights) {
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0) {
            weights.replaceAll((k, v) -> v / sum);
        }
    }
}
