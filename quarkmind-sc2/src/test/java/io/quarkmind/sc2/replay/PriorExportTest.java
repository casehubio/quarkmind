package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("report")
class PriorExportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DATA_ROOT = Path.of("../quarkmind-classifier/data/reconstituted");
    private static final Path OUTPUT_DIR = Path.of("src/main/resources/priors");
    private static final String[] MATCHUPS = {"vs_terran", "vs_zerg", "vs_protoss"};

    @Test
    void exportPriorsAndCpts() throws Exception {
        if (!Files.exists(DATA_ROOT)) {
            System.out.println("SKIP: reconstituted data not available at " + DATA_ROOT);
            return;
        }

        Files.createDirectories(OUTPUT_DIR);

        var byMatchup = new HashMap<String, List<ScoutingObservationSequence>>();
        var labelsByMatchupPhase = new HashMap<String, List<String>>();

        int processed = 0;
        int skipped = 0;

        List<Path> datasets;
        try (Stream<Path> ds = Files.list(DATA_ROOT)) {
            datasets = ds.filter(Files::isDirectory).toList();
        }

        for (var dataset : datasets) {
            List<Path> jsons;
            try (Stream<Path> js = Files.list(dataset)) {
                jsons = js.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".label.json")).toList();
            }

            for (var jsonPath : jsons) {
                var labelPath = Path.of(jsonPath.toString().replace(".json", ".label.json"));
                if (!Files.exists(labelPath)) { skipped++; continue; }

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

                        @SuppressWarnings("unchecked")
                        var playerMap = (Map<String, Map<String, Object>>) gameJson.get("ToonPlayerDescMap");
                        var playerInfo = playerMap.get(entry.getKey());
                        if (playerInfo == null) continue;
                        String raceStr = (String) playerInfo.get("race");
                        Race playerRace = Race.valueOf(raceStr.toUpperCase());
                        Race opponentRace = ClassifierLabelMapper.opponentRaceEnum(matchup);

                        var observations = ScoutingObservationExtractor.extract(gameJson, pid);
                        var seq = new ScoutingObservationSequence(
                            hash, pid, playerRace, opponentRace,
                            observations, label);

                        byMatchup.computeIfAbsent(matchup, k -> new ArrayList<>()).add(seq);

                        for (var phase : GamePhase.values()) {
                            String key = matchup + "_" + phase.name();
                            labelsByMatchupPhase.computeIfAbsent(key, k -> new ArrayList<>()).add(label);
                        }
                    }
                    processed++;
                } catch (Exception e) {
                    skipped++;
                }
            }
        }

        System.out.printf("Processed %d replays, skipped %d%n", processed, skipped);
        assertTrue(processed > 100, "Need at least 100 replays for meaningful priors");

        for (String matchup : MATCHUPS) {
            var sequences = byMatchup.getOrDefault(matchup, List.of());
            if (sequences.isEmpty()) continue;

            for (var phase : GamePhase.values()) {
                String key = matchup + "_" + phase.name();
                var labels = labelsByMatchupPhase.getOrDefault(key, List.of());

                var allArchetypes = new LinkedHashSet<String>();
                Race opponentRace = ClassifierLabelMapper.opponentRaceEnum(matchup);
                for (var arch : StrategyArchetype.values()) {
                    if (arch.race() == opponentRace
                            && arch.phase() == phase
                            && !arch.name().contains("UNKNOWN")) {
                        allArchetypes.add(ClassifierLabelMapper.archLabel(arch));
                    }
                }
                if (allArchetypes.isEmpty()) continue;

                // Map classifier labels to archetype labels
                var mappedLabels = new ArrayList<String>();
                for (String classLabel : labels) {
                    var mapped = ClassifierLabelMapper.mapToArchetypes(classLabel, matchup, phase);
                    for (var arch : mapped) {
                        mappedLabels.add(ClassifierLabelMapper.archLabel(arch));
                    }
                }

                // Map sequence ground truth labels too
                var mappedSequences = new ArrayList<ScoutingObservationSequence>();
                for (var seq : sequences) {
                    var mapped = ClassifierLabelMapper.mapToArchetypes(seq.groundTruthStrategy(), matchup, phase);
                    for (var arch : mapped) {
                        String archLabel = ClassifierLabelMapper.archLabel(arch);
                        mappedSequences.add(new ScoutingObservationSequence(
                            seq.replayHash(), seq.playerId(), seq.playerRace(), seq.opponentRace(),
                            seq.observations(), archLabel));
                    }
                }

                var priors = PriorExportPipeline.learnPriors(mappedLabels, allArchetypes);
                var cpts = PriorExportPipeline.learnObservationCpts(mappedSequences, allArchetypes);
                var stateIndex = PriorExportPipeline.collectStateIndex(sequences);
                PriorExportPipeline.exportToJson(OUTPUT_DIR, phase.name(), matchup, priors, cpts, stateIndex);

                System.out.printf("%s %s: %d labels, %d sequences, %d prior entries%n",
                    phase, matchup, labels.size(), sequences.size(), priors.size());
            }
        }

        // Verify output files
        int filesWritten = 0;
        for (String matchup : MATCHUPS) {
            for (var phase : GamePhase.values()) {
                var priorsFile = OUTPUT_DIR.resolve("opponent-priors-" + phase.name() + "-" + matchup + ".json");
                if (Files.exists(priorsFile)) {
                    filesWritten++;
                    var loadedPriors = MAPPER.readValue(priorsFile.toFile(),
                        new TypeReference<Map<String, Double>>() {});
                    assertFalse(loadedPriors.isEmpty(), priorsFile + " is empty");
                    double sum = loadedPriors.values().stream().mapToDouble(d -> d).sum();
                    assertEquals(1.0, sum, 0.01, priorsFile + " priors don't sum to 1.0");
                    for (var entry : loadedPriors.entrySet()) {
                        assertTrue(entry.getValue() > 0,
                            priorsFile + " has zero prior for " + entry.getKey());
                    }
                }
            }
        }
        System.out.printf("Wrote %d prior files to %s%n", filesWritten, OUTPUT_DIR);
        assertTrue(filesWritten > 0, "Should have written at least one prior file");
    }
}
