package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.Race;
import io.quarkmind.sc2.detector.BinaryDetectorRunner;
import io.quarkmind.sc2.detector.DetectorCatalog;
import io.quarkmind.sc2.detector.DetectorRegistry;
import io.quarkmind.sc2.detector.HypothesisPriorLoader;
import io.quarkmind.sc2.detector.HypothesisTrackerFusion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("benchmark")
class ResponseSelectionCalibrationTest {

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
    void responseSelection_producesRecommendations_afterScouting() {
        Race[]      enemyRaces = {Race.TERRAN, Race.ZERG, Race.PROTOSS};
        GamePhase[] phases     = {GamePhase.EARLY, GamePhase.MID, GamePhase.LATE};
        Race        ourRace    = Race.PROTOSS;

        StringBuilder report = new StringBuilder();
        report.append("=== Response Selection Calibration ===\n\n");
        report.append(String.format("%-8s %-6s  %-30s %-6s  %-30s %-6s%n",
                                    "Enemy", "Phase", "Opponent Leader", "Conf", "Response Leader", "Conf"));
        report.append("-".repeat(95)).append("\n");

        int totalScenarios   = 0;
        int responseProduced = 0;

        Map<String, String> evenContext = Map.of(
                "our_economy_state", "even",
                "our_army_state", "even",
                "our_tech_state", "even"
                                                );

        for (Race enemyRace : enemyRaces) {
            for (GamePhase phase : phases) {
                var allActive = registry.getActiveDetectors(phase, enemyRace);
                var allKeys   = new HashSet<String>();
                for (var def : allActive) {allKeys.add(def.key(enemyRace));}

                totalScenarios++;

                var priors    = HypothesisPriorLoader.loadOpponentPriors(phase, enemyRace);
                var obsCpts   = HypothesisPriorLoader.loadObservationCpts(phase, enemyRace);
                var overrides = HypothesisPriorLoader.loadExpertOverrides(enemyRace);
                var detCpts   = buildDetectorCpts(allKeys, enemyRace);

                var responsePriors = syntheticResponsePriors(priors);

                var fusion = new HypothesisTrackerFusion(
                        registry, priors, detCpts, obsCpts, Map.of(), null,
                        responsePriors, overrides);
                fusion.initialize(List.copyOf(allKeys), phase, enemyRace);

                float[] temporal    = new float[10 * FeatureIndexMaps.FEATURES_PER_WINDOW];
                float[] mapFeatures = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};
                var features = new StrategyFeatures(Map.of(
                        "temporal", new float[][]{temporal},
                        "map", new float[][]{mapFeatures}));

                var toEval = fusion.getDetectorsToEvaluate(allKeys);
                var scores = runner.runActiveDetectors(phase, enemyRace, features, toEval);
                fusion.classify(scores, List.of(), 4032);

                String oppLeader = fusion.opponentLeader() != null ? fusion.opponentLeader() : "—";
                double oppConf   = fusion.opponentWeights().getOrDefault(oppLeader, 0.0);

                List<PatternAssessment> response = fusion.selectResponse(ourRace, evenContext);

                String respLeader = response.isEmpty() ? "—" : response.getFirst().archetype().name();
                double respConf   = response.isEmpty() ? 0.0 : response.getFirst().confidence();

                if (!response.isEmpty()) {responseProduced++;}

                report.append(String.format("%-8s %-6s  %-30s %5.2f  %-30s %5.2f%n",
                                            enemyRace, phase, oppLeader, oppConf, respLeader, respConf));
            }
        }

        report.append("\n=== Summary ===\n");
        report.append(String.format("Scenarios: %d%n", totalScenarios));
        report.append(String.format("Response recommendations produced: %d/%d%n",
                                    responseProduced, totalScenarios));
        System.out.println(report);

        assertTrue(responseProduced > 0,
                   "Response selection must produce at least one recommendation across all scenarios");
    }

    @Test
    void responseSelection_contextVariables_shiftRecommendation() {
        Race      enemyRace = Race.TERRAN;
        GamePhase phase     = GamePhase.EARLY;

        var priors    = HypothesisPriorLoader.loadOpponentPriors(phase, enemyRace);
        var obsCpts   = HypothesisPriorLoader.loadObservationCpts(phase, enemyRace);
        var overrides = HypothesisPriorLoader.loadExpertOverrides(enemyRace);
        var allActive = registry.getActiveDetectors(phase, enemyRace);
        var allKeys   = new HashSet<String>();
        for (var def : allActive) {allKeys.add(def.key(enemyRace));}
        var detCpts = buildDetectorCpts(allKeys, enemyRace);

        var responsePriors = HypothesisPriorLoader.loadResponsePriors(enemyRace);
        if (responsePriors.isEmpty()) {
            responsePriors = syntheticResponsePriors(priors);
        }
        var contextPriors = HypothesisPriorLoader.loadContextResponsePriors(enemyRace);

        var fusion = new HypothesisTrackerFusion(
                registry, priors, detCpts, obsCpts, Map.of(), null,
                responsePriors, overrides);
        fusion.initialize(List.copyOf(allKeys), phase, enemyRace);

        var behind = Map.of(
                "our_economy_state", "behind",
                "our_army_state", "smaller",
                "our_tech_state", "behind");
        var ahead = Map.of(
                "our_economy_state", "ahead",
                "our_army_state", "larger",
                "our_tech_state", "ahead");

        var responseBehind = fusion.selectResponse(Race.PROTOSS, behind);
        var responseAhead  = fusion.selectResponse(Race.PROTOSS, ahead);

        System.out.println("=== Context Variable Sensitivity ===");
        System.out.println("Behind context: " +
                           (responseBehind.isEmpty() ? "—" : responseBehind.getFirst().archetype()));
        System.out.println("Ahead context:  " +
                           (responseAhead.isEmpty() ? "—" : responseAhead.getFirst().archetype()));
        System.out.println("Context CPTs loaded: " + (contextPriors != null));

        if (contextPriors != null && !responseBehind.isEmpty() && !responseAhead.isEmpty()) {
            boolean anyDifferent = false;
            for (int i = 0; i < Math.min(responseBehind.size(), responseAhead.size()); i++) {
                if (Math.abs(responseBehind.get(i).confidence() - responseAhead.get(i).confidence()) > 0.001) {
                    anyDifferent = true;
                    break;
                }
            }
            assertTrue(anyDifferent,
                       "Context-dependent CPTs should produce different distributions for behind vs ahead");
        }
    }

    private Map<String, Map<String, Double>> buildDetectorCpts(
            java.util.Set<String> detectorKeys, Race race) {
        Map<String, Map<String, Double>> cpts = new HashMap<>();
        var config = registry.config();
        for (var def : config.detectors()) {
            String key = def.key(race);
            if (!detectorKeys.contains(key)) continue;
            var positives = def.positiveArchetypes().getOrDefault(race, List.of());
            Map<String, Double> archCpt = new HashMap<>();
            for (String pos : positives) {
                archCpt.put(pos, 0.85);
            }
            cpts.put(key, archCpt);
        }
        return cpts;
    }

    private static Map<String, Map<String, Double>> syntheticResponsePriors(
            Map<String, Double> opponentPriors) {
        String[] ourStrategies = {"GATEWAY_RUSH", "MACRO", "BLINK_STALKER",
                                  "STALKER_COLOSSUS", "CHARGELOT_ARCHON"};
        Map<String, Map<String, Double>> result = new HashMap<>();
        int                              i      = 0;
        for (String opponent : opponentPriors.keySet()) {
            Map<String, Double> responseDist = new HashMap<>();
            for (int j = 0; j < ourStrategies.length; j++) {
                responseDist.put(ourStrategies[j],
                                 j == (i % ourStrategies.length) ? 0.5 : 0.5 / (ourStrategies.length - 1));
            }
            result.put(opponent, responseDist);
            i++;
        }
        return result;
    }

}
