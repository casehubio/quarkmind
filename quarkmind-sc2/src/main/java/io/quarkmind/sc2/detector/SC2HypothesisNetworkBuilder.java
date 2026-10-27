package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.inference.bayes.BayesNetworkBuilder;
import io.quarkmind.inference.bayes.CompositionBuilder;
import io.quarkmind.inference.bayes.HypothesisTracker;
import io.quarkmind.inference.bayes.JunctionTree;
import io.quarkmind.inference.bayes.NetworkComposition;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SC2HypothesisNetworkBuilder {

    static String[] filteredArchetypes(Race race, GamePhase phase,
                                       Map<String, Double> priors) {
        return priors.keySet().stream().sorted().toArray(String[]::new);
    }

    public static HypothesisTracker buildOpponentTracker(
            Race enemyRace,
            GamePhase phase,
            Map<String, Double> learnedPriors,
            List<String> activeDetectorNames,
            Map<String, Map<String, Double>> detectorCpts,
            HypothesisPriorLoader.ObservationCpts observationCpts) {
        String[] archetypes = filteredArchetypes(enemyRace, phase, learnedPriors);
        JunctionTree intelTree = buildIntelTree(archetypes, learnedPriors,
                                                activeDetectorNames, detectorCpts, observationCpts);

        NetworkComposition composition = CompositionBuilder.create("sc2-scouting")
                                                           .slot("intel", intelTree)
                                                           .build();

        return buildHypothesisTracker(composition, archetypes, learnedPriors);
    }

    public static HypothesisTracker buildTracker(
            Race enemyRace, GamePhase phase, Race ourRace,
            Map<String, Double> learnedPriors,
            List<String> activeDetectorNames,
            Map<String, Map<String, Double>> detectorCpts,
            HypothesisPriorLoader.ObservationCpts observationCpts,
            Map<String, Map<String, Double>> responsePriors,
            List<HypothesisPriorLoader.ExpertOverride> expertOverrides) {

        String[] archetypes = filteredArchetypes(enemyRace, phase, learnedPriors);
        JunctionTree intelTree = buildIntelTree(archetypes, learnedPriors,
                                                activeDetectorNames, detectorCpts, observationCpts);

        var compBuilder = CompositionBuilder.create("sc2-scouting")
                                            .slot("intel", intelTree);

        if (responsePriors != null && !responsePriors.isEmpty()) {
            String[] ourStrategies = filteredOurArchetypes(responsePriors);
            double[] enemyPriors   = normalizedPriors(archetypes, learnedPriors);
            double[] tiledCpt = buildTiledResponseCpt(archetypes, ourStrategies,
                                                      responsePriors, expertOverrides);

            var responseBuilder = BayesNetworkBuilder.create("response");
            responseBuilder.variable("enemy_strategy", archetypes).prior(enemyPriors);
            responseBuilder.variable("our_economy_state", "behind", "even", "ahead")
                           .prior(1.0 / 3, 1.0 / 3, 1.0 / 3);
            responseBuilder.variable("our_army_state", "smaller", "even", "larger")
                           .prior(1.0 / 3, 1.0 / 3, 1.0 / 3);
            responseBuilder.variable("our_tech_state", "behind", "even", "ahead")
                           .prior(1.0 / 3, 1.0 / 3, 1.0 / 3);
            responseBuilder.variable("our_strategy", ourStrategies)
                           .given("enemy_strategy", "our_economy_state",
                                  "our_army_state", "our_tech_state")
                           .cpt(tiledCpt);
            JunctionTree responseTree = responseBuilder.buildJunctionTree();

            compBuilder.slot("response", responseTree);
            compBuilder.link("intel", "enemy_strategy").to("response", "enemy_strategy");
        }

        NetworkComposition composition = compBuilder.build();
        return buildHypothesisTracker(composition, archetypes, learnedPriors);
    }

    static String[] filteredOurArchetypes(Map<String, Map<String, Double>> responsePriors) {
        return responsePriors.values().stream()
                             .flatMap(m -> m.keySet().stream())
                             .distinct().sorted().toArray(String[]::new);
    }

    private static JunctionTree buildIntelTree(
            String[] archetypes,
            Map<String, Double> learnedPriors,
            List<String> activeDetectorNames,
            Map<String, Map<String, Double>> detectorCpts,
            HypothesisPriorLoader.ObservationCpts observationCpts) {

        int      N          = archetypes.length;
        double[] priorArray = normalizedPriors(archetypes, learnedPriors);

        var intelBuilder = BayesNetworkBuilder.create("intel");
        intelBuilder.variable("enemy_strategy", archetypes).prior(priorArray);

        for (var obsEntry : observationCpts.cpts().entrySet()) {
            String   varName = obsEntry.getKey();
            String[] states  = observationCpts.stateIndex().get(varName);
            if (states == null) {continue;}

            double[] flatCpt = new double[N * states.length];
            for (int a = 0; a < N; a++) {
                double[] row = obsEntry.getValue().get(archetypes[a]);
                if (row == null) {
                    double uniform = 1.0 / states.length;
                    for (int s = 0; s < states.length; s++) {
                        flatCpt[a * states.length + s] = uniform;
                    }
                } else {
                    double uniform = 1.0 / states.length;
                    for (int s = 0; s < states.length; s++) {
                        double val = s < row.length ? row[s] : uniform;
                        flatCpt[a * states.length + s] =
                                Math.max(0.01, Math.min(0.99, val));
                    }
                }
            }
            intelBuilder.variable(varName, states)
                        .given("enemy_strategy").cpt(flatCpt);
        }

        for (String detName : activeDetectorNames) {
            Map<String, Double> detCpt  = detectorCpts.get(detName);
            double[]            flatCpt = new double[N * 2];
            for (int a = 0; a < N; a++) {
                double pHigh = 0.5;
                if (detCpt != null) {
                    pHigh = detCpt.getOrDefault(archetypes[a], 0.5);
                }
                pHigh              = Math.max(0.01, Math.min(0.99, pHigh));
                flatCpt[a * 2]     = pHigh;
                flatCpt[a * 2 + 1] = 1.0 - pHigh;
            }
            intelBuilder.variable(detName, "HIGH", "LOW")
                        .given("enemy_strategy").cpt(flatCpt);
        }

        double[] markerCpt = new double[N * N];
        for (int a = 0; a < N; a++) {
            for (int s = 0; s < N; s++) {
                markerCpt[a * N + s] = (a == s) ? 0.8 : (0.2 / (N - 1));
            }
        }
        intelBuilder.variable("drools_evidence", archetypes)
                    .given("enemy_strategy").cpt(markerCpt);

        return intelBuilder.buildJunctionTree();
    }

    private static double[] normalizedPriors(String[] archetypes, Map<String, Double> learnedPriors) {
        int      N          = archetypes.length;
        double[] priorArray = new double[N];
        double   priorSum   = 0;
        for (int i = 0; i < N; i++) {
            priorArray[i] = learnedPriors.getOrDefault(archetypes[i], 1.0 / N);
            priorSum += priorArray[i];
        }
        for (int i = 0; i < N; i++) {
            priorArray[i] /= priorSum;
        }
        return priorArray;
    }

    private static HypothesisTracker buildHypothesisTracker(
            NetworkComposition composition, String[] archetypes, Map<String, Double> learnedPriors) {
        int      N          = archetypes.length;
        double[] priorArray = normalizedPriors(archetypes, learnedPriors);
        var      builder    = HypothesisTracker.builder(composition);
        for (int i = 0; i < N; i++) {
            double[] override = new double[N];
            Arrays.fill(override, 0.05 / (N - 1));
            override[i] = 0.85;
            double overrideSum = 0;
            for (double v : override) {overrideSum += v;}
            for (int j = 0; j < N; j++) {override[j] /= overrideSum;}

            builder.hypothesis(archetypes[i])
                   .priorWeight(priorArray[i])
                   .priorOverride("intel", "enemy_strategy", override);
        }
        builder.eliminationThreshold(0.01)
               .resolutionThreshold(0.95);
        return builder.build();
    }

    static double[] buildTiledResponseCpt(
            String[] enemyArchetypes, String[] ourStrategies,
            Map<String, Map<String, Double>> responsePriors,
            List<HypothesisPriorLoader.ExpertOverride> expertOverrides) {
        int      contextCombinations = 27;
        int      totalRows           = enemyArchetypes.length * contextCombinations;
        double[] cpt                 = new double[totalRows * ourStrategies.length];

        for (int e = 0; e < enemyArchetypes.length; e++) {
            String              enemy = enemyArchetypes[e];
            Map<String, Double> raw   = responsePriors.getOrDefault(enemy, Map.of());

            Map<String, Double> priorDist = new LinkedHashMap<>();
            for (String our : ourStrategies) {
                priorDist.put(our, raw.getOrDefault(our, 1.0 / ourStrategies.length));
            }
            double sum = priorDist.values().stream().mapToDouble(d -> d).sum();
            priorDist.replaceAll((k, v) -> v / sum);

            List<HypothesisPriorLoader.ExpertOverride> matching = expertOverrides == null
                                                                  ? List.of()
                                                                  : expertOverrides.stream().filter(ov -> ov.whenOpponent().equals(enemy)).toList();
            if (!matching.isEmpty()) {
                priorDist = HypothesisPriorLoader.applyExpertOverrides(priorDist, matching);
            }

            double[] row = new double[ourStrategies.length];
            for (int s = 0; s < ourStrategies.length; s++) {
                row[s] = Math.max(0.01, Math.min(0.99,
                                                 priorDist.getOrDefault(ourStrategies[s], 1.0 / ourStrategies.length)));
            }
            double rowSum = 0;
            for (double v : row) {rowSum += v;}
            for (int s = 0; s < row.length; s++) {row[s] /= rowSum;}

            for (int ctx = 0; ctx < contextCombinations; ctx++) {
                int offset = (e * contextCombinations + ctx) * ourStrategies.length;
                System.arraycopy(row, 0, cpt, offset, row.length);
            }
        }
        return cpt;
    }

}
