package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.TransitionPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("report")
class StrategyTransitionExportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path RECONSTITUTED_DIR =
            Path.of("../quarkmind-classifier/data/reconstituted");
    private static final Path OUTPUT_DIR =
            Path.of("src/main/resources/transitions");
    private static final int[] CHECKPOINTS = {1344, 4032, 6720, 9408, 13440};
    private static final double ALPHA = 10.0;
    private static final int TAXONOMY_BOOST = 50;
    private static final double DISCOVERY_THRESHOLD = 0.05;

    @Test
    void exportStrategyTransitionCpts() throws Exception {
        assertTrue(Files.isDirectory(RECONSTITUTED_DIR),
                   "Reconstituted data required at " + RECONSTITUTED_DIR);

        StrategyTaxonomy taxonomy = new StrategyTaxonomy();
        taxonomy.init();

        Map<Race, Map<String, Map<String, Integer>>> transitionCounts = new EnumMap<>(Race.class);
        Map<Race, Integer> replayCounts = new EnumMap<>(Race.class);
        for (Race r : new Race[]{Race.PROTOSS, Race.TERRAN, Race.ZERG}) {
            transitionCounts.put(r, new LinkedHashMap<>());
            replayCounts.put(r, 0);
        }

        int totalReplays = 0;
        for (Path datasetDir : listSubDirs(RECONSTITUTED_DIR)) {
            List<Path> replayFiles = listJsonFiles(datasetDir);
            for (Path replayFile : replayFiles) {
                Path labelFile = Path.of(replayFile.toString().replace(".json", ".label.json"));
                if (!Files.exists(labelFile)) continue;

                totalReplays++;
                JsonNode replay = MAPPER.readTree(replayFile.toFile());
                JsonNode labels = MAPPER.readTree(labelFile.toFile());
                JsonNode perspectives = labels.get("perspectives");
                if (perspectives == null) continue;

                var perspectiveIter = perspectives.fields();
                while (perspectiveIter.hasNext()) {
                    var perspEntry = perspectiveIter.next();
                    JsonNode p = perspEntry.getValue();
                    String raceStr = p.has("race") ? p.get("race").asText() : null;
                    Race enemyRace = parseRace(raceStr);
                    if (enemyRace == null) continue;

                    String[] strategyAtCheckpoint = new String[CHECKPOINTS.length];
                    for (int c = 0; c < CHECKPOINTS.length; c++) {
                        String cpKey = "coarse_" + (CHECKPOINTS[c] / 1344) + "min";
                        if (p.has(cpKey)) {
                            strategyAtCheckpoint[c] = p.get(cpKey).asText();
                        }
                    }

                    replayCounts.merge(enemyRace, 1, Integer::sum);
                    for (int c = 0; c < CHECKPOINTS.length - 1; c++) {
                        String from = strategyAtCheckpoint[c];
                        String to = strategyAtCheckpoint[c + 1];
                        if (from == null || to == null) continue;
                        if (!isValidArchetype(from) || !isValidArchetype(to)) continue;

                        transitionCounts.get(enemyRace)
                                .computeIfAbsent(from, k -> new LinkedHashMap<>())
                                .merge(to, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.println("Processed " + totalReplays + " replays");
        for (Race race : new Race[]{Race.PROTOSS, Race.TERRAN, Race.ZERG}) {
            System.out.println(race + ": " + replayCounts.get(race) + " perspectives, "
                    + transitionCounts.get(race).size() + " source archetypes");
        }

        for (Race race : new Race[]{Race.PROTOSS, Race.TERRAN, Race.ZERG}) {
            Map<String, Map<String, Double>> cpts = buildSmoothedCpts(
                    transitionCounts.get(race), race, taxonomy, replayCounts.get(race));

            Path output = OUTPUT_DIR.resolve("transition-cpts-vs_" + race.name().toLowerCase() + ".json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), cpts);
            System.out.println("Wrote " + output + " (" + cpts.size() + " rows)");

            for (var row : cpts.entrySet()) {
                double sum = row.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
                assertEquals(1.0, sum, 0.01, "row " + row.getKey() + " should sum to 1.0");
                for (double p : row.getValue().values()) {
                    assertTrue(p < 0.95, "no transition should be > 0.95");
                }
            }

            for (StrategyArchetype from : StrategyArchetype.values()) {
                if (from.race() != race) continue;
                for (StrategyArchetype to : StrategyArchetype.values()) {
                    if (to.race() != race) continue;
                    var tp = taxonomy.transitionPath(from, to);
                    if (tp.isPresent() && cpts.containsKey(from.name())) {
                        double prob = cpts.get(from.name()).getOrDefault(to.name(), 0.0);
                        assertTrue(prob > 0.01,
                                "taxonomy transition " + from + " -> " + to + " should have prob > 0.01, got " + prob);
                    }
                }
            }
        }
    }

    private Map<String, Map<String, Double>> buildSmoothedCpts(
            Map<String, Map<String, Integer>> rawCounts, Race race,
            StrategyTaxonomy taxonomy, int totalPerspectives) {

        String[] allArchetypes = java.util.Arrays.stream(StrategyArchetype.values())
                .filter(a -> a.race() == race)
                .map(Enum::name)
                .sorted()
                .toArray(String[]::new);

        Map<String, Map<String, Double>> cpts = new LinkedHashMap<>();
        for (String from : allArchetypes) {
            Map<String, Integer> row = rawCounts.getOrDefault(from, Map.of());
            int rowTotal = row.values().stream().mapToInt(Integer::intValue).sum();

            if (rowTotal == 0 && !hasTaxonomyTransition(from, race, taxonomy)) continue;

            Map<String, Double> probs = new LinkedHashMap<>();
            double denominator = rowTotal + ALPHA * allArchetypes.length;

            for (String to : allArchetypes) {
                int count = row.getOrDefault(to, 0);
                double smoothedCount = count + ALPHA;

                if (hasTaxonomyTransitionPair(from, to, taxonomy)) {
                    smoothedCount += TAXONOMY_BOOST;
                    denominator += TAXONOMY_BOOST;
                }

                double freq = (double) count / Math.max(1, totalPerspectives);
                if (freq >= DISCOVERY_THRESHOLD || hasTaxonomyTransitionPair(from, to, taxonomy) || count > 0) {
                    probs.put(to, smoothedCount);
                }
            }

            double probSum = probs.values().stream().mapToDouble(Double::doubleValue).sum();
            probs.replaceAll((k, v) -> v / probSum);
            cpts.put(from, probs);
        }

        return cpts;
    }

    private boolean hasTaxonomyTransition(String from, Race race, StrategyTaxonomy taxonomy) {
        StrategyArchetype fromArch = StrategyArchetype.valueOf(from);
        for (StrategyArchetype to : StrategyArchetype.values()) {
            if (to.race() != race) continue;
            if (taxonomy.transitionPath(fromArch, to).isPresent()) return true;
        }
        return false;
    }

    private boolean hasTaxonomyTransitionPair(String from, String to, StrategyTaxonomy taxonomy) {
        try {
            return taxonomy.transitionPath(StrategyArchetype.valueOf(from), StrategyArchetype.valueOf(to)).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Race parseRace(String raceStr) {
        if (raceStr == null) return null;
        return switch (raceStr.toLowerCase()) {
            case "protoss", "prot" -> Race.PROTOSS;
            case "terran", "terr" -> Race.TERRAN;
            case "zerg" -> Race.ZERG;
            default -> null;
        };
    }

    private static boolean isValidArchetype(String name) {
        try {
            StrategyArchetype.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<Path> listSubDirs(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static List<Path> listJsonFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".json")
                            && !p.toString().endsWith(".label.json"))
                    .sorted().toList();
        }
    }
}
