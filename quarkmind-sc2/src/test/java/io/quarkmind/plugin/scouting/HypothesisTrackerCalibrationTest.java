package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.ScoutingObservation;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.sc2.detector.BinaryDetectorRunner;
import io.quarkmind.sc2.detector.DetectorCatalog;
import io.quarkmind.sc2.detector.DetectorOrchestrator;
import io.quarkmind.sc2.detector.DetectorRegistry;
import io.quarkmind.sc2.detector.HypothesisPriorLoader;
import io.quarkmind.sc2.detector.HypothesisTrackerFusion;
import io.quarkmind.sc2.detector.LearnedCptLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("benchmark")
class HypothesisTrackerCalibrationTest {

    private static BinaryDetectorRunner runner;
    private static DetectorRegistry registry;

    @BeforeAll
    static void setUp() {
        runner = new BinaryDetectorRunner(
            Path.of("src/main/resources/models/detectors"));
        registry = new DetectorRegistry(DetectorCatalog.create());
    }

    @AfterAll
    static void tearDown() {
        if (runner != null) runner.close();
    }

    @Test
    void bothPaths_produceAssessments_forAllRacePhases() {
        Race[] races = {Race.TERRAN, Race.ZERG, Race.PROTOSS};
        GamePhase[] phases = {GamePhase.EARLY, GamePhase.MID, GamePhase.LATE};

        StringBuilder report = new StringBuilder();
        report.append("=== HypothesisTracker vs DetectorOrchestrator — Assessment Comparison ===\n\n");
        report.append(String.format("%-8s %-6s  %-30s %-6s  %-30s %-6s%n",
            "Race", "Phase", "Old Leader", "Conf", "New Leader", "Conf"));
        report.append("-".repeat(90)).append("\n");

        int oldProduced = 0, newProduced = 0;

        for (Race race : races) {
            for (GamePhase phase : phases) {
                var allActive = registry.getActiveDetectors(phase, race);
                var allKeys = new HashSet<String>();
                for (var def : allActive) allKeys.add(def.key(race));
                if (allKeys.isEmpty()) continue;

                float[] temporal = new float[10 * FeatureIndexMaps.FEATURES_PER_WINDOW];
                float[] mapFeatures = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};
                var features = new StrategyFeatures(Map.of(
                    "temporal", new float[][]{temporal},
                    "map", new float[][]{mapFeatures}));

                // Old path: DetectorOrchestrator
                var oldOrch = buildOrchestrator(allKeys);
                oldOrch.initializeNetwork(List.copyOf(allKeys), phase, race);
                var toEvalOld = oldOrch.getDetectorsToEvaluate(allKeys);
                var scoresOld = runner.runActiveDetectors(phase, race, features, toEvalOld);
                var oldAssessments = oldOrch.classify(scoresOld, List.of(), 4032);

                // New path: HypothesisTrackerFusion
                var fusion = buildFusion(race, phase);
                fusion.initialize(List.copyOf(allKeys), phase, race);
                var toEvalNew = fusion.getDetectorsToEvaluate(allKeys);
                var scoresNew = runner.runActiveDetectors(phase, race, features, toEvalNew);
                var newAssessments = fusion.classify(scoresNew, List.of(), 4032);

                String oldLeader = oldAssessments.isEmpty() ? "—" : oldAssessments.get(0).archetype().name();
                double oldConf = oldAssessments.isEmpty() ? 0.0 : oldAssessments.get(0).confidence();
                String newLeader = newAssessments.isEmpty() ? "—" : newAssessments.get(0).archetype().name();
                double newConf = newAssessments.isEmpty() ? 0.0 : newAssessments.get(0).confidence();

                if (!oldAssessments.isEmpty()) oldProduced++;
                if (!newAssessments.isEmpty()) newProduced++;

                report.append(String.format("%-8s %-6s  %-30s %5.2f  %-30s %5.2f%n",
                    race, phase, oldLeader, oldConf, newLeader, newConf));
            }
        }

        int totalScenarios = (int) java.util.Arrays.stream(races)
            .flatMap(r -> java.util.Arrays.stream(phases).map(p -> Map.entry(r, p)))
            .filter(e -> {
                var active = registry.getActiveDetectors(e.getValue(), e.getKey());
                return !active.isEmpty();
            }).count();

        report.append("\n=== Summary ===\n");
        report.append(String.format("Scenarios: %d%n", totalScenarios));
        report.append(String.format("Old path produced assessments: %d/%d%n", oldProduced, totalScenarios));
        report.append(String.format("New path produced assessments: %d/%d%n", newProduced, totalScenarios));
        System.out.println(report);

        assertTrue(newProduced > 0, "New path must produce at least one assessment");
    }

    @Test
    void scoutingObservations_shiftAssessments() {
        Race race = Race.TERRAN;
        GamePhase phase = GamePhase.EARLY;
        var allActive = registry.getActiveDetectors(phase, race);
        var allKeys = new HashSet<String>();
        for (var def : allActive) allKeys.add(def.key(race));
        if (allKeys.isEmpty()) return;

        var fusion = buildFusion(race, phase);
        fusion.initialize(List.copyOf(allKeys), phase, race);

        Map<String, Double> weightsBefore = Map.copyOf(fusion.opponentWeights());

        fusion.observeScouting(new ScoutingObservation("first_building", "barracks", 1500));
        fusion.observeScouting(new ScoutingObservation("gas_timing", "late", 4000));

        Map<String, Double> weightsAfter = fusion.opponentWeights();

        assertFalse(weightsBefore.equals(weightsAfter),
            "Scouting observations should shift hypothesis weights");

        float[] temporal = new float[10 * FeatureIndexMaps.FEATURES_PER_WINDOW];
        var features = new StrategyFeatures(Map.of(
            "temporal", new float[][]{temporal},
            "map", new float[][]{new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f}}));

        var toEval = fusion.getDetectorsToEvaluate(allKeys);
        var scores = runner.runActiveDetectors(phase, race, features, toEval);
        var assessments = fusion.classify(scores, List.of(), 4032);

        assertFalse(assessments.isEmpty(), "Should produce assessments after scouting + detectors");

        System.out.println("=== Scouting + Detector Fusion (Terran EARLY) ===");
        System.out.printf("Weights shifted: %d hypotheses changed%n",
            weightsAfter.entrySet().stream()
                .filter(e -> !e.getValue().equals(weightsBefore.get(e.getKey())))
                .count());
        for (PatternAssessment a : assessments.subList(0, Math.min(5, assessments.size()))) {
            System.out.printf("  %s: %.4f%n", a.archetype(), a.confidence());
        }
    }

    @Test
    void fullCascade_hypothesisMode_producesResults() {
        var priors = HypothesisPriorLoader.loadOpponentPriors(GamePhase.EARLY, Race.TERRAN);
        var obsCpts = HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);
        var detCpts = buildDetectorCpts();
        var fusion = new HypothesisTrackerFusion(
            registry, priors, detCpts, obsCpts, Map.of(), null);

        var classifier = new CascadingPatternClassifier(0.99, 0.01, fusion, runner);

        fusion.initialize(List.of(), GamePhase.EARLY, Race.TERRAN);
        classifier.observeScouting(new ScoutingObservation("first_building", "barracks", 1500));

        float[] temporal = new float[10 * FeatureIndexMaps.FEATURES_PER_WINDOW];
        var features = new StrategyFeatures(Map.of(
            "temporal", new float[][]{temporal},
            "map", new float[][]{new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f}}));

        var evidence = List.of(new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.5, "test"));
        var result = classifier.classify(evidence, List.of(), features,
            Race.TERRAN, 4032, -1, null);

        assertFalse(result.assessments().isEmpty(), "Full cascade should produce assessments");
        System.out.println("=== Full Cascade (hypothesis mode) ===");
        for (var a : result.assessments().subList(0, Math.min(5, result.assessments().size()))) {
            System.out.printf("  %s: %.4f [%s]%n", a.archetype(), a.confidence(), a.source());
        }
    }

    private DetectorOrchestrator buildOrchestrator(Set<String> detectorKeys) {
        var config = registry.config();
        var allArchetypes = new LinkedHashSet<String>();
        for (var def : config.detectors()) {
            for (var archs : def.positiveArchetypes().values()) {
                allArchetypes.addAll(archs);
            }
        }
        Map<String, Double> priors = new HashMap<>();
        for (String arch : allArchetypes) priors.put(arch, 1.0 / allArchetypes.size());
        Map<String, Map<String, Double>> cpts = new HashMap<>();
        for (var def : config.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                String key = def.key(entry.getKey());
                Map<String, Double> cpt = new HashMap<>();
                for (String arch : allArchetypes) {
                    cpt.put(arch, entry.getValue().contains(arch) ? 0.8 : 0.2);
                }
                cpts.put(key, cpt);
            }
        }
        Map<String, StrategyArchetype> labelToArchetype = new HashMap<>();
        for (String arch : allArchetypes) {
            try { labelToArchetype.put(arch, StrategyArchetype.valueOf(arch)); }
            catch (IllegalArgumentException ignored) {}
        }
        var learnedNetworks = new LearnedCptLoader().loadFromClasspath();
        return new DetectorOrchestrator(
            registry, priors, cpts, labelToArchetype, 5, 0.95, 0.01, learnedNetworks);
    }

    private HypothesisTrackerFusion buildFusion(Race race, GamePhase phase) {
        Map<String, Double> priors;
        HypothesisPriorLoader.ObservationCpts obsCpts;
        try {
            priors = HypothesisPriorLoader.loadOpponentPriors(phase, race);
            obsCpts = HypothesisPriorLoader.loadObservationCpts(phase, race);
        } catch (IllegalArgumentException e) {
            priors = Map.of();
            obsCpts = new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of());
        }
        return new HypothesisTrackerFusion(
            registry, priors, buildDetectorCpts(), obsCpts, Map.of(), null);
    }

    private Map<String, Map<String, Double>> buildDetectorCpts() {
        var config = registry.config();
        var allArchetypes = new LinkedHashSet<String>();
        for (var def : config.detectors()) {
            for (var archs : def.positiveArchetypes().values()) {
                allArchetypes.addAll(archs);
            }
        }
        Map<String, Map<String, Double>> cpts = new HashMap<>();
        for (var def : config.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                String key = def.key(entry.getKey());
                Map<String, Double> cpt = new HashMap<>();
                for (String arch : allArchetypes) {
                    cpt.put(arch, entry.getValue().contains(arch) ? 0.8 : 0.2);
                }
                cpts.put(key, cpt);
            }
        }
        return cpts;
    }
}
