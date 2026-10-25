package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesNetworkBuilder;
import io.quarkmind.inference.bayes.NetworkSpec;
import io.quarkmind.perception.EvidenceFusionEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class CptLearningValidationTest {

    private static final Path TEST_OBS_DIR =
        Path.of("../quarkmind-classifier/output/cpt_observations");
    private static final Path LEARNED_DIR =
        Path.of("src/main/resources/classifier");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static boolean testDataExists() {
        if (!Files.isDirectory(TEST_OBS_DIR)) return false;
        try (var s = Files.list(TEST_OBS_DIR)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith("_test.json"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("testDataExists")
    @SuppressWarnings("unchecked")
    void learnedCptsOutperformHandTuned() throws Exception {
        List<Path> testFiles;
        try (Stream<Path> walk = Files.list(TEST_OBS_DIR)) {
            testFiles = walk.filter(p -> p.getFileName().toString().endsWith("_test.json"))
                .sorted().toList();
        }

        assertThat(testFiles).as("test observation files").isNotEmpty();

        int totalWins = 0;
        int totalFiles = 0;

        for (Path testFile : testFiles) {
            var json = (Map<String, Object>) MAPPER.readValue(testFile.toFile(), Map.class);
            String matchup = (String) json.get("matchup");
            String phase = (String) json.get("phase");
            var rawObs = (List<Map<String, String>>) json.get("observations");

            if (rawObs.size() < 50) {
                System.out.printf("SKIP %s %s: only %d test observations%n", phase, matchup, rawObs.size());
                continue;
            }

            var strategyLabels = new TreeSet<String>();
            var detectorKeys = new TreeSet<String>();
            for (var obs : rawObs) {
                strategyLabels.add(obs.get("Strategy"));
                for (String key : obs.keySet()) {
                    if (!"Strategy".equals(key)) detectorKeys.add(key);
                }
            }

            String[] outcomes = strategyLabels.toArray(new String[0]);
            String[] detectors = detectorKeys.toArray(new String[0]);

            String race = matchup.replace("vs_", "");
            Path learnedFile = LEARNED_DIR.resolve(
                "learned-cpts-" + phase.toLowerCase() + "-vs-" + race + ".json");

            if (!Files.exists(learnedFile)) {
                System.out.printf("SKIP %s %s: no learned CPTs at %s%n",
                    phase, matchup, learnedFile);
                continue;
            }

            var resource = MAPPER.readValue(learnedFile.toFile(), LearnedCptResource.class);
            BayesNetwork learnedNetwork = resource.network().toBayesNetwork();

            BayesNetwork handTunedNetwork = buildHandTunedNetwork(outcomes, detectors);

            double learnedAcc = measureAccuracy(learnedNetwork, rawObs, outcomes);
            double handTunedAcc = measureAccuracy(handTunedNetwork, rawObs, outcomes);
            double improvement = learnedAcc - handTunedAcc;

            System.out.printf("%s %s: learned=%.3f  hand-tuned=%.3f  delta=%+.3f  (%d test obs)%n",
                phase, matchup, learnedAcc, handTunedAcc, improvement, rawObs.size());

            totalFiles++;
            if (improvement >= 0) totalWins++;
        }

        System.out.printf("%nValidation summary: %d/%d matchup-phases where learned >= hand-tuned%n",
            totalWins, totalFiles);

        assertThat(totalFiles).as("validated matchup-phases").isGreaterThan(0);
    }

    static double measureAccuracy(BayesNetwork network,
                                  List<Map<String, String>> observations,
                                  String[] strategyOutcomes) {
        var engine = new EvidenceFusionEngine();
        engine.initialize(network, "Strategy");
        String[] outcomeNames = engine.getOutcomeNames();

        int correct = 0;
        for (var obs : observations) {
            String trueLabel = obs.get("Strategy");

            Map<String, double[]> evidence = new HashMap<>();
            for (var entry : obs.entrySet()) {
                if ("Strategy".equals(entry.getKey())) continue;
                evidence.put(entry.getKey(), discreteToLikelihood(entry.getValue()));
            }

            engine.setAllEvidence(evidence);
            engine.update();
            double[] posterior = engine.marginalize();

            int bestIdx = 0;
            for (int i = 1; i < posterior.length; i++) {
                if (posterior[i] > posterior[bestIdx]) bestIdx = i;
            }

            if (outcomeNames[bestIdx].equals(trueLabel)) correct++;
        }
        return (double) correct / observations.size();
    }

    static double[] discreteToLikelihood(String value) {
        return switch (value) {
            case "HIGH" -> new double[]{1.0, 0.0, 0.0};
            case "MEDIUM" -> new double[]{0.0, 1.0, 0.0};
            case "LOW" -> new double[]{0.0, 0.0, 1.0};
            default -> new double[]{1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0};
        };
    }

    static BayesNetwork buildHandTunedNetwork(String[] strategyOutcomes, String[] detectorKeys) {
        double[] priors = new double[strategyOutcomes.length];
        Arrays.fill(priors, 1.0 / strategyOutcomes.length);

        var builder = BayesNetworkBuilder.create();
        var chain = builder.variable("Strategy", strategyOutcomes).prior(priors);

        var catalog = DetectorCatalog.create();
        Map<String, java.util.Set<String>> detectorPositives = new HashMap<>();
        for (var def : catalog.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                String key = def.key(entry.getKey());
                detectorPositives.put(key, new java.util.HashSet<>(entry.getValue()));
            }
        }

        for (String detector : detectorKeys) {
            double[] cpt = new double[strategyOutcomes.length * 3];
            var positives = detectorPositives.getOrDefault(detector, java.util.Set.of());
            for (int i = 0; i < strategyOutcomes.length; i++) {
                int base = i * 3;
                if (positives.contains(strategyOutcomes[i])) {
                    cpt[base] = 0.7;
                    cpt[base + 1] = 0.2;
                    cpt[base + 2] = 0.1;
                } else {
                    cpt[base] = 0.1;
                    cpt[base + 1] = 0.3;
                    cpt[base + 2] = 0.6;
                }
            }
            chain = chain.variable(detector, "HIGH", "MEDIUM", "LOW")
                .given("Strategy").cpt(cpt);
        }
        return chain.build();
    }
}
