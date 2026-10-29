package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.ScoutingObservationSequence;
import io.quarkmind.inference.bayes.HypothesisPriorLearner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PriorExportPipeline {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double SMOOTHING = 1.0;
    private static final double CPT_FLOOR = 0.01;
    private static final double CPT_CEILING = 0.99;

    public static Map<String, Double> learnPriors(List<String> labels, Set<String> allArchetypes) {
        return HypothesisPriorLearner.fromOutcomes(labels, allArchetypes, SMOOTHING);
    }

    public record ResponseOutcome(
            String ourStrategy,
            String opponentStrategy,
            String matchup,
            boolean won
    ) {}

    public record ContextResponseOutcome(
            String ourStrategy,
            String opponentStrategy,
            String matchup,
            boolean won,
            String economyState,
            String armyState,
            String techState
    ) {}

    public static Map<String, String> discretizeContext(
            double ourIncome, double enemyIncome,
            int ourArmyCount, int enemyArmyCount,
            long ourTechCount, long enemyTechCount) {
        Map<String, String> context = new LinkedHashMap<>();

        double incomeRatio = enemyIncome > 0 ? ourIncome / enemyIncome : 1.0;
        context.put("economyState",
                    incomeRatio < 0.8 ? "behind" : incomeRatio > 1.2 ? "ahead" : "even");

        double armyRatio = enemyArmyCount > 0 ? (double) ourArmyCount / enemyArmyCount : 1.0;
        context.put("armyState",
                    armyRatio < 0.7 ? "smaller" : armyRatio > 1.3 ? "larger" : "even");

        context.put("techState",
                    ourTechCount < enemyTechCount ? "behind"
                                                  : ourTechCount > enemyTechCount ? "ahead" : "even");

        return context;
    }

    public static String contextKey(String economyState, String armyState, String techState) {
        return economyState + ":" + armyState + ":" + techState;
    }


    public static Map<String, Map<String, Double>> learnResponsePriors(
            List<ResponseOutcome> outcomes, Set<String> allOurArchetypes) {
        var wins = outcomes.stream().filter(ResponseOutcome::won).toList();
        Map<String, List<ResponseOutcome>> byOpponent = wins.stream()
                                                            .collect(Collectors.groupingBy(ResponseOutcome::opponentStrategy));

        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (var entry : byOpponent.entrySet()) {
            Map<String, Double> counts = new LinkedHashMap<>();
            for (String arch : allOurArchetypes) {
                counts.put(arch, SMOOTHING);
            }
            for (var outcome : entry.getValue()) {
                counts.merge(outcome.ourStrategy(), 1.0, Double::sum);
            }
            double              total      = counts.values().stream().mapToDouble(d -> d).sum();
            Map<String, Double> normalized = new LinkedHashMap<>();
            for (var e : counts.entrySet()) {
                normalized.put(e.getKey(), e.getValue() / total);
            }
            result.put(entry.getKey(), normalized);
        }
        return result;
    }

    public static void exportResponsePriorsToJson(Path outputDir, String matchup,
                                                  Map<String, Map<String, Double>> responsePriors) throws IOException {
        Files.createDirectories(outputDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                outputDir.resolve("response-priors-" + matchup + ".json").toFile(),
                responsePriors);
    }

    public static Map<String, Map<String, Map<String, Double>>> learnContextResponsePriors(
            List<ContextResponseOutcome> outcomes, Set<String> allOurArchetypes,
            Map<String, Map<String, Double>> marginalPriors, double alpha) {

        var wins = outcomes.stream().filter(ContextResponseOutcome::won).toList();

        Map<String, Map<String, List<ContextResponseOutcome>>> byOpponentAndContext = new LinkedHashMap<>();
        for (var w : wins) {
            String ctxKey = contextKey(w.economyState(), w.armyState(), w.techState());
            byOpponentAndContext
                    .computeIfAbsent(w.opponentStrategy(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(ctxKey, k -> new ArrayList<>())
                    .add(w);
        }

        Map<String, Map<String, Map<String, Double>>> result = new LinkedHashMap<>();
        for (var opEntry : byOpponentAndContext.entrySet()) {
            String              opponent = opEntry.getKey();
            Map<String, Double> marginal = marginalPriors.getOrDefault(opponent, Map.of());

            Map<String, Map<String, Double>> contextMap = new LinkedHashMap<>();
            for (var ctxEntry : opEntry.getValue().entrySet()) {
                String                       ctxKey   = ctxEntry.getKey();
                List<ContextResponseOutcome> cellWins = ctxEntry.getValue();

                Map<String, Double> counts = new LinkedHashMap<>();
                for (String arch : allOurArchetypes) {
                    counts.put(arch, SMOOTHING);
                }
                for (var w : cellWins) {
                    counts.merge(w.ourStrategy(), 1.0, Double::sum);
                }
                double              cellTotal = counts.values().stream().mapToDouble(d -> d).sum();
                Map<String, Double> pCell     = new LinkedHashMap<>();
                for (var e : counts.entrySet()) {
                    pCell.put(e.getKey(), e.getValue() / cellTotal);
                }

                int    n              = cellWins.size();
                double cellWeight     = (double) n / (n + alpha);
                double marginalWeight = alpha / (n + alpha);

                Map<String, Double> blended = new LinkedHashMap<>();
                for (String arch : allOurArchetypes) {
                    double pc = pCell.getOrDefault(arch, 1.0 / allOurArchetypes.size());
                    double pm = marginal.getOrDefault(arch, 1.0 / allOurArchetypes.size());
                    blended.put(arch, cellWeight * pc + marginalWeight * pm);
                }

                double sum = blended.values().stream().mapToDouble(d -> d).sum();
                blended.replaceAll((k, v) -> v / sum);

                contextMap.put(ctxKey, blended);
            }
            result.put(opponent, contextMap);
        }
        return result;
    }

    public static void exportContextResponsePriorsToJson(
            Path outputDir, String matchup,
            Map<String, Map<String, Map<String, Double>>> contextPriors,
            double alpha) throws IOException {
        Files.createDirectories(outputDir);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("alpha", alpha);
        export.put("priors", contextPriors);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                outputDir.resolve("response-cpts-context-" + matchup + ".json").toFile(),
                export);
    }


    public static Map<String, Map<String, double[]>> learnObservationCpts(
            List<ScoutingObservationSequence> sequences, Set<String> allArchetypes) {

        var variableStates = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var seq : sequences) {
            for (var obs : seq.observations()) {
                variableStates.computeIfAbsent(obs.variable(), k -> new LinkedHashSet<>())
                    .add(obs.state());
            }
        }

        var counts = new HashMap<String, Map<String, Map<String, Double>>>();
        for (var seq : sequences) {
            String label = seq.groundTruthStrategy();
            if (!allArchetypes.contains(label)) continue;
            for (var obs : seq.observations()) {
                counts.computeIfAbsent(obs.variable(), k -> new HashMap<>())
                    .computeIfAbsent(label, k -> new HashMap<>())
                    .merge(obs.state(), 1.0, Double::sum);
            }
        }

        var result = new LinkedHashMap<String, Map<String, double[]>>();
        for (var varEntry : variableStates.entrySet()) {
            String variable = varEntry.getKey();
            var states = new ArrayList<>(varEntry.getValue());
            var archCpts = new LinkedHashMap<String, double[]>();

            for (String archetype : allArchetypes) {
                double[] dist = new double[states.size()];
                var archCounts = counts.getOrDefault(variable, Map.of())
                    .getOrDefault(archetype, Map.of());

                double total = 0;
                for (int i = 0; i < states.size(); i++) {
                    dist[i] = archCounts.getOrDefault(states.get(i), 0.0) + SMOOTHING;
                    total += dist[i];
                }
                for (int i = 0; i < dist.length; i++) {
                    dist[i] = Math.max(CPT_FLOOR, Math.min(CPT_CEILING, dist[i] / total));
                }
                double sum = 0;
                for (double d : dist) sum += d;
                for (int i = 0; i < dist.length; i++) dist[i] /= sum;

                archCpts.put(archetype, dist);
            }
            result.put(variable, archCpts);
        }

        return result;
    }

    public static Map<String, List<String>> collectStateIndex(
            List<ScoutingObservationSequence> sequences) {
        var seen = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var seq : sequences) {
            for (var obs : seq.observations()) {
                seen.computeIfAbsent(obs.variable(), k -> new LinkedHashSet<>())
                    .add(obs.state());
            }
        }
        var stateIndex = new LinkedHashMap<String, List<String>>();
        for (var entry : seen.entrySet()) {
            stateIndex.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return stateIndex;
    }

    public static void exportToJson(Path outputDir, String phase, String matchup,
            Map<String, Double> priors,
            Map<String, Map<String, double[]>> cpts,
            Map<String, List<String>> stateIndex) throws IOException {

        Files.createDirectories(outputDir);
        String suffix = phase + "-" + matchup + ".json";

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
            outputDir.resolve("opponent-priors-" + suffix).toFile(), priors);

        var cptExport = new LinkedHashMap<String, Object>();
        cptExport.put("stateIndex", stateIndex);
        cptExport.put("cpts", cpts);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
            outputDir.resolve("observation-cpts-" + suffix).toFile(), cptExport);
    }
}
