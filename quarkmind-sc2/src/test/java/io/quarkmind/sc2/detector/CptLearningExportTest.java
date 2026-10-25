package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesNetworkBuilder;
import io.quarkmind.inference.bayes.NetworkSpec;
import io.quarkmind.inference.bayes.ParameterLearner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class CptLearningExportTest {

    private static final Path OBSERVATIONS_DIR =
        Path.of("../quarkmind-classifier/output/cpt_observations");
    private static final Path OUTPUT_DIR =
        Path.of("src/main/resources/classifier");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static boolean observationsExist() {
        return Files.isDirectory(OBSERVATIONS_DIR);
    }

    @Test
    @EnabledIf("observationsExist")
    @SuppressWarnings("unchecked")
    void learnAndExportCpts() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        String gitSha = getGitSha();
        int totalFiles = 0;
        int totalObservations = 0;

        List<Path> obsFiles;
        try (Stream<Path> walk = Files.list(OBSERVATIONS_DIR)) {
            obsFiles = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }

        assertThat(obsFiles).as("observation files").isNotEmpty();

        for (Path obsFile : obsFiles) {
            var json = (Map<String, Object>) MAPPER.readValue(obsFile.toFile(), Map.class);
            String matchup = (String) json.get("matchup");
            String phase = (String) json.get("phase");
            int replayCount = ((Number) json.get("replayCount")).intValue();
            int observationCount = ((Number) json.get("observationCount")).intValue();
            var rawObs = (List<Map<String, String>>) json.get("observations");

            assertThat(rawObs).as("observations in " + obsFile.getFileName())
                .hasSizeGreaterThanOrEqualTo(100);

            var strategyLabels = new TreeSet<String>();
            var detectorKeys = new TreeSet<String>();
            for (var obs : rawObs) {
                strategyLabels.add(obs.get("Strategy"));
                for (String key : obs.keySet()) {
                    if (!"Strategy".equals(key)) detectorKeys.add(key);
                }
            }

            String[] strategyOutcomes = strategyLabels.toArray(new String[0]);
            BayesNetwork structure = buildStructure(strategyOutcomes,
                detectorKeys.toArray(new String[0]));

            List<Map<String, String>> observations = new ArrayList<>(rawObs);
            BayesNetwork learned = ParameterLearner.fit(structure, observations);

            String networkName = "learned-cpts-" + phase.toLowerCase() + "-" + matchup;
            NetworkSpec spec = NetworkSpec.fromBayesNetwork(learned, networkName,
                "Learned from " + replayCount + " replays, " + observationCount + " observations");

            var resource = new LearnedCptResource(
                matchup,
                phase,
                new LearnedCptResource.LearnedFrom(
                    replayCount,
                    observationCount,
                    gitSha,
                    Instant.now().toString()
                ),
                spec
            );

            String race = matchup.replace("vs_", "");
            String outFilename = "learned-cpts-" + phase.toLowerCase() + "-vs-" + race + ".json";
            Path outPath = OUTPUT_DIR.resolve(outFilename);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outPath.toFile(), resource);

            printLearnedSummary(phase, race, observationCount, strategyOutcomes, learned);
            totalFiles++;
            totalObservations += observationCount;
        }

        System.out.printf("%nCPT learning complete: %d files exported, %d total observations%n",
            totalFiles, totalObservations);
        assertThat(totalFiles).as("exported files").isGreaterThan(0);
    }

    static BayesNetwork buildStructure(String[] strategyOutcomes, String[] detectorKeys) {
        double[] priors = new double[strategyOutcomes.length];
        Arrays.fill(priors, 1.0 / strategyOutcomes.length);

        var builder = BayesNetworkBuilder.create();
        var chain = builder.variable("Strategy", strategyOutcomes).prior(priors);

        for (String detector : detectorKeys) {
            int rows = strategyOutcomes.length;
            double[] uniformCpt = new double[rows * 3];
            Arrays.fill(uniformCpt, 1.0 / 3.0);
            chain = chain.variable(detector, "HIGH", "MEDIUM", "LOW")
                .given("Strategy").cpt(uniformCpt);
        }

        return chain.build();
    }

    private static void printLearnedSummary(String phase, String race,
                                            int observationCount,
                                            String[] strategyOutcomes,
                                            BayesNetwork learned) {
        System.out.printf("Learned CPTs for %s vs %s: %d observations, %d unique strategies%n",
            phase, race, observationCount, strategyOutcomes.length);

        for (var node : learned) {
            var var = node.getContent();
            if (var.getGiven().length == 0) {
                double[][] table = var.getProbabilityTable();
                System.out.printf("  Strategy priors: ");
                var outcomes = var.getOutcomes();
                for (int i = 0; i < outcomes.length; i++) {
                    System.out.printf("%s=%.4f ", outcomes[i], table[0][i]);
                }
                System.out.println();
            }
        }
    }

    private static String getGitSha() {
        try {
            var proc = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true).start();
            return new String(proc.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
