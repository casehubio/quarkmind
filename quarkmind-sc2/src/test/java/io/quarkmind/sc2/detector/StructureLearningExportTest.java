package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.inference.bayes.CptSpec;
import io.quarkmind.inference.bayes.EdgeSpec;
import io.quarkmind.inference.bayes.NetworkSpec;
import io.quarkmind.inference.bayes.ParameterLearner;
import io.quarkmind.inference.bayes.SpecStatus;
import io.quarkmind.inference.bayes.StructuralConstraints;
import io.quarkmind.inference.bayes.StructureLearner;
import io.quarkmind.inference.bayes.ModelSelector;
import io.quarkmind.inference.bayes.VariableSpec;
import io.quarkmind.sc2.replay.ClassifierLabelMapper;
import io.quarkmind.sc2.replay.ScoutingObservationExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("report")
class StructureLearningExportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DATA_ROOT = Path.of("../quarkmind-classifier/data/reconstituted");
    private static final Path OUTPUT_DIR = Path.of("src/main/resources/classifier");
    private static final String[] MATCHUPS = {"vs_terran", "vs_zerg", "vs_protoss"};
    private static final double PC_ALPHA = 0.05;
    private static final int HC_RESTARTS = 5;
    private static final long HC_SEED = 42L;
    private static final double IMPROVEMENT_THRESHOLD = 0.05;

    @Test
    void learnAndExportNetworkStructure() throws Exception {
        if (!Files.exists(DATA_ROOT)) {
            System.out.println("SKIP: reconstituted data not available at " + DATA_ROOT);
            return;
        }

        var tabularByMatchup = extractTabularData();
        assertTrue(!tabularByMatchup.isEmpty(), "Need at least 1 matchup");

        for (String matchup : MATCHUPS) {
            var allRows = tabularByMatchup.getOrDefault(matchup, List.of());
            if (allRows.isEmpty()) continue;

            Race opponentRace = ClassifierLabelMapper.opponentRaceEnum(matchup);

            for (var phase : GamePhase.values()) {
                var mapped = filterAndMapRows(allRows, matchup, phase, opponentRace);
                if (mapped.isEmpty()) continue;

                var rows = mapped.stream().map(MappedRow::row).toList();
                System.out.printf("%n=== %s %s: %d rows ===%n", matchup, phase, rows.size());

                var results = compareVariants(rows, matchup, phase);
                exportWinner(results, matchup, phase);
            }
        }
    }

    // --- data extraction ---

    record TabularRow(String replayHash, int playerId, String rawLabel,
                      String matchup, Map<String, String> observations) {
        Map<String, String> toRow(String archetypeLabel) {
            var row = new LinkedHashMap<>(observations);
            row.put("enemy_strategy", archetypeLabel);
            return row;
        }
    }

    record MappedRow(TabularRow source, Map<String, String> row) {}

    private Map<String, List<TabularRow>> extractTabularData() throws Exception {
        var byMatchup = new HashMap<String, List<TabularRow>>();
        int processed = 0;

        List<Path> datasets;
        try (Stream<Path> ds = Files.list(DATA_ROOT)) {
            datasets = ds.filter(Files::isDirectory).toList();
        }

        for (var dataset : datasets) {
            List<Path> jsons;
            try (Stream<Path> js = Files.list(dataset)) {
                jsons = js.filter(p -> p.toString().endsWith(".json")
                    && !p.toString().endsWith(".label.json")).toList();
            }

            for (var jsonPath : jsons) {
                var labelPath = Path.of(jsonPath.toString().replace(".json", ".label.json"));
                if (!Files.exists(labelPath)) continue;

                try {
                    var gameJson = MAPPER.readValue(jsonPath.toFile(),
                        new TypeReference<Map<String, Object>>() {});
                    var labelJson = MAPPER.readValue(labelPath.toFile(),
                        new TypeReference<Map<String, Object>>() {});

                    @SuppressWarnings("unchecked")
                    var perspectives = (Map<String, Map<String, Object>>) labelJson.get("perspectives");
                    String hash = (String) labelJson.get("replayHash");

                    for (var entry : perspectives.entrySet()) {
                        int pid = Integer.parseInt(entry.getKey());
                        var pData = entry.getValue();
                        String matchup = (String) pData.get("matchup");
                        String label = (String) pData.get("fineGrainedLabel");

                        var observations = ScoutingObservationExtractor.extract(gameJson, pid);
                        Map<String, String> obsMap = new LinkedHashMap<>();
                        for (var obs : observations) {
                            obsMap.put(obs.variable(), obs.state());
                        }

                        byMatchup.computeIfAbsent(matchup, k -> new ArrayList<>())
                            .add(new TabularRow(hash, pid, label, matchup, obsMap));
                    }
                    processed++;
                } catch (Exception e) { /* skip malformed */ }
            }
        }

        System.out.printf("Extracted tabular data from %d replays%n", processed);
        return byMatchup;
    }

    // --- filtering and mapping ---

    private List<MappedRow> filterAndMapRows(
            List<TabularRow> allRows, String matchup, GamePhase phase, Race opponentRace) {
        var result = new ArrayList<MappedRow>();
        for (var row : allRows) {
            var archetypes = ClassifierLabelMapper.mapToArchetypes(row.rawLabel(), matchup, phase);
            for (var arch : archetypes) {
                if (arch.race() == opponentRace && arch.phase() == phase
                        && !arch.name().contains("UNKNOWN")) {
                    result.add(new MappedRow(row, row.toRow(ClassifierLabelMapper.archLabel(arch))));
                }
            }
        }
        return result;
    }

    // --- structure learning and comparison ---

    record VariantScore(String name, double logLikelihood, double bicScore, NetworkSpec spec) {}
    record ComparisonResult(VariantScore best, NetworkSpec starSpec,
                            boolean switchToLearned, double improvement,
                            List<VariantScore> allVariants) {}

    private ComparisonResult compareVariants(
            List<Map<String, String>> rows,
            String matchup, GamePhase phase) {
        String[] archetypes = rows.stream()
                                  .map(r -> r.get("enemy_strategy")).distinct().sorted().toArray(String[]::new);

        // Hand-specified star baseline
        var starSpec   = buildStarSpec(archetypes, rows);
        var starFitted = ParameterLearner.fit(starSpec.toBayesNetwork(), rows);
        var starScore  = ModelSelector.bic(starFitted, rows);
        System.out.printf("  Star (data-fitted): BIC=%.2f  LL=%.4f%n",
                          starScore.score(), starScore.logLikelihood());

        var variants = new ArrayList<VariantScore>();
        variants.add(new VariantScore("star", starScore.logLikelihood(), starScore.score(), starSpec));

        // Variant 1: Unconstrained PC
        try {
            var pcResult = StructureLearner.pc(rows, PC_ALPHA);
            var pcFitted = pcResult.toBayesNetwork(rows);
            var pcScore  = ModelSelector.bic(pcFitted, rows);
            variants.add(new VariantScore("pc_unconstrained", pcScore.logLikelihood(),
                                          pcScore.score(), pcResult.networkSpec()));
            System.out.printf("  PC unconstrained:   BIC=%.2f  LL=%.4f (edges: %d)%n",
                              pcScore.score(), pcScore.logLikelihood(), pcResult.networkSpec().edges().size());
        } catch (Exception e) {
            System.out.printf("  PC unconstrained:   FAILED (%s)%n", e.getMessage());
        }

        // Variant 2: Unconstrained hill-climbing
        try {
            var hcResult = StructureLearner.hillClimbing(rows);
            var hcFitted = hcResult.toBayesNetwork(rows);
            var hcScore  = ModelSelector.bic(hcFitted, rows);
            variants.add(new VariantScore("hc_unconstrained", hcScore.logLikelihood(),
                                          hcScore.score(), hcResult.networkSpec()));
            System.out.printf("  HC unconstrained:   BIC=%.2f  LL=%.4f (edges: %d)%n",
                              hcScore.score(), hcScore.logLikelihood(), hcResult.networkSpec().edges().size());
        } catch (Exception e) {
            System.out.printf("  HC unconstrained:   FAILED (%s)%n", e.getMessage());
        }

        // Variant 3: Constrained HC (domain invariants)
        try {
            var domainConstraints = new StructuralConstraints(
                    Set.of(), Set.of(), 3);
            var hcConstrainedResult = StructureLearner.hillClimbing(
                    rows, domainConstraints, HC_RESTARTS, HC_SEED);
            var hcConstrainedFitted = hcConstrainedResult.toBayesNetwork(rows);
            var hcConstrainedScore  = ModelSelector.bic(hcConstrainedFitted, rows);
            variants.add(new VariantScore("hc_constrained", hcConstrainedScore.logLikelihood(),
                                          hcConstrainedScore.score(), hcConstrainedResult.networkSpec()));
            System.out.printf("  HC constrained:     BIC=%.2f  LL=%.4f (edges: %d)%n",
                              hcConstrainedScore.score(), hcConstrainedScore.logLikelihood(),
                              hcConstrainedResult.networkSpec().edges().size());
        } catch (Exception e) {
            System.out.printf("  HC constrained:     FAILED (%s)%n", e.getMessage());
        }

        // Variant 4: Domain-knowledge-seeded HC
        try {
            var domainSpec = buildDomainPriorSpec(archetypes, rows);
            var domainConstraints = StructuralConstraints.fromSpec(domainSpec);
            var hcSeededResult = StructureLearner.hillClimbing(
                rows, domainConstraints, HC_RESTARTS, HC_SEED);
            var hcSeededFitted = hcSeededResult.toBayesNetwork(rows);
            var hcSeededScore = ModelSelector.bic(hcSeededFitted, rows);
            variants.add(new VariantScore("hc_domain_seeded", hcSeededScore.logLikelihood(),
                hcSeededScore.score(), hcSeededResult.networkSpec()));
            System.out.printf("  HC domain-seeded:   BIC=%.2f  LL=%.4f (edges: %d)%n",
                hcSeededScore.score(), hcSeededScore.logLikelihood(),
                hcSeededResult.networkSpec().edges().size());
        } catch (Exception e) {
            System.out.printf("  HC domain-seeded:   FAILED (%s)%n", e.getMessage());
        }

        // Lower BIC = better model (BIC penalizes complexity)
        var best = variants.stream()
                           .min(Comparator.comparingDouble(VariantScore::bicScore))
                           .orElseThrow();

        double llImprovement = Math.abs(starScore.logLikelihood()) > 0
                               ? (best.logLikelihood() - starScore.logLikelihood()) / Math.abs(starScore.logLikelihood())
                               : 0;
        boolean switchToLearned = !best.name().equals("star")
                                  && llImprovement >= IMPROVEMENT_THRESHOLD;

        System.out.printf("  Best: %s (LL improvement: %.2f%% — %s threshold)%n",
                          best.name(), llImprovement * 100,
                          switchToLearned ? "MEETS" : "below");

        return new ComparisonResult(best, starSpec, switchToLearned, llImprovement, variants);
    }

    private NetworkSpec buildStarSpec(String[] archetypes, List<Map<String, String>> rows) {
        var variableStates = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            for (var entry : row.entrySet()) {
                variableStates.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                if (!variableStates.get(entry.getKey()).contains(entry.getValue())) {
                    variableStates.get(entry.getKey()).add(entry.getValue());
                }
            }
        }

        var variables = new ArrayList<VariableSpec>();
        var edges = new ArrayList<EdgeSpec>();

        variables.add(new VariableSpec(
            "enemy_strategy", variableStates.get("enemy_strategy"),
            "", SpecStatus.LOCKED, null));

        for (var entry : variableStates.entrySet()) {
            if (entry.getKey().equals("enemy_strategy")) continue;
            variables.add(new VariableSpec(
                entry.getKey(), entry.getValue(), "", SpecStatus.ACCEPTED, null));
            edges.add(new EdgeSpec(
                "enemy_strategy", entry.getKey(), "", SpecStatus.ACCEPTED));
        }

        return new NetworkSpec("star", variables, edges, "Naive Bayes star topology");
    }

    private NetworkSpec buildDomainPriorSpec(String[] archetypes, List<Map<String, String>> rows) {
        var variableStates = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            for (var entry : row.entrySet()) {
                variableStates.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                if (!variableStates.get(entry.getKey()).contains(entry.getValue())) {
                    variableStates.get(entry.getKey()).add(entry.getValue());
                }
            }
        }

        var variables = new ArrayList<VariableSpec>();
        var edges     = new ArrayList<EdgeSpec>();

        variables.add(new VariableSpec(
                "enemy_strategy", variableStates.get("enemy_strategy"),
                "", SpecStatus.LOCKED, null));

        for (var entry : variableStates.entrySet()) {
            if (entry.getKey().equals("enemy_strategy")) {continue;}
            variables.add(new VariableSpec(
                    entry.getKey(), entry.getValue(), "", SpecStatus.ACCEPTED, null));

            SpecStatus edgeStatus = switch (entry.getKey()) {
                case "gas_timing", "first_building", "expansion_timing" -> SpecStatus.LOCKED;
                default -> SpecStatus.ACCEPTED;
            };
            edges.add(new EdgeSpec(
                    "enemy_strategy", entry.getKey(), "Domain prior", edgeStatus));
        }

        return new NetworkSpec("domain-seeded", variables, edges,
                               "SC2 domain-knowledge-seeded topology");
    }


    // --- export ---

    private void exportWinner(ComparisonResult result, String matchup, GamePhase phase)
            throws Exception {
        NetworkSpec winnerSpec;
        if (result.switchToLearned()) {
            winnerSpec = result.best().spec();
        } else {
            winnerSpec = result.starSpec();
        }

        winnerSpec = augmentWithDroolsEvidence(winnerSpec);

        var resource = new LinkedHashMap<String, Object>();
        resource.put("matchup", matchup);
        resource.put("phase", phase.name());
        resource.put("topology", result.best().name());
        resource.put("improvement", result.improvement());
        resource.put("switchToLearned", result.switchToLearned());
        resource.put("allVariants", result.allVariants().stream()
            .map(v -> Map.of("name", v.name(),
                             "logLikelihood", v.logLikelihood(),
                             "bicScore", v.bicScore(),
                             "edges", v.spec().edges().size()))
            .toList());
        resource.put("network", winnerSpec);

        Files.createDirectories(OUTPUT_DIR);
        Path outputPath = OUTPUT_DIR.resolve(
            "learned-topology-" + phase.name().toLowerCase() + "-" + matchup.replace('_', '-') + ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), resource);
        System.out.printf("  Exported: %s%n", outputPath);
    }

    private NetworkSpec augmentWithDroolsEvidence(NetworkSpec spec) {
        var archetypes = spec.variables().stream()
            .filter(v -> v.name().equals("enemy_strategy"))
            .findFirst()
            .map(VariableSpec::outcomes)
            .orElseThrow();

        int N = archetypes.size();
        double[][] markerRows = new double[N][N];
        for (int a = 0; a < N; a++) {
            for (int s = 0; s < N; s++) {
                markerRows[a][s] = (a == s) ? 0.8 : (0.2 / (N - 1));
            }
        }

        var droolsVar = new VariableSpec(
            "drools_evidence", archetypes, "Platform integration",
            SpecStatus.ACCEPTED, new CptSpec.DirectCpt(markerRows));
        var droolsEdge = new EdgeSpec(
            "enemy_strategy", "drools_evidence", "Drools evidence injection",
            SpecStatus.ACCEPTED);

        return spec.withVariable(droolsVar).withEdge(droolsEdge);
    }
}
