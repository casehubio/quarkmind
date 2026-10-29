package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.sc2.replay.PriorExportPipeline.ContextResponseOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("report")
class ContextResponsePriorExportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path RECONSTITUTED_DIR =
            Path.of("../quarkmind-classifier/data/reconstituted");
    private static final Path OUTPUT_DIR =
            Path.of("src/main/resources/priors");
    private static final int CHECKPOINT_3MIN = 4032;
    private static final int CHECKPOINT_5MIN = 6720;
    private static final double ALPHA = 10.0;

    private static final Set<String> TECH_BUILDING_NAMES = Set.of(
            "CyberneticsCore", "TwilightCouncil", "RoboticsFacility", "Stargate",
            "DarkShrine", "TemplarArchives", "FleetBeacon", "RoboticsBay", "Forge",
            "EngineeringBay", "Armory", "GhostAcademy", "Factory", "Starport", "FusionCore",
            "SpawningPool", "EvolutionChamber", "RoachWarren", "BanelingNest",
            "HydraliskDen", "LurkerDenMP", "InfestationPit", "Spire",
            "GreaterSpire", "UltraliskCavern");

    @Test
    void exportContextResponsePriors() throws Exception {
        assertTrue(Files.isDirectory(RECONSTITUTED_DIR),
                   "Reconstituted data required at " + RECONSTITUTED_DIR);

        Map<String, List<ContextResponseOutcome>> byMatchup = new LinkedHashMap<>();
        Map<String, Set<String>> allOurByMatchup = new LinkedHashMap<>();
        int totalOutcomes = 0;
        int totalReplays = 0;

        for (Path datasetDir : listSubDirs(RECONSTITUTED_DIR)) {
            List<Path> replayFiles = listJsonFiles(datasetDir);
            for (Path replayFile : replayFiles) {
                Path labelFile = Path.of(replayFile.toString()
                        .replace(".json", ".label.json"));
                if (!Files.exists(labelFile)) continue;

                totalReplays++;
                JsonNode replay = MAPPER.readTree(replayFile.toFile());
                JsonNode labels = MAPPER.readTree(labelFile.toFile());
                JsonNode perspectives = labels.get("perspectives");
                if (perspectives == null) continue;

                JsonNode header = replay.get("header");
                if (header == null) continue;
                int totalLoops = header.has("elapsedGameLoops")
                        ? header.get("elapsedGameLoops").asInt() : 0;

                JsonNode events = replay.get("trackerEvents");
                if (events == null || !events.isArray()) continue;

                JsonNode toonMap = replay.get("ToonPlayerDescMap");
                if (toonMap == null) continue;

                var fieldIt = perspectives.fields();
                while (fieldIt.hasNext()) {
                    var pEntry = fieldIt.next();
                    String pid = pEntry.getKey();
                    JsonNode pLabel = pEntry.getValue();
                    String matchup = pLabel.has("matchup") ? pLabel.get("matchup").asText() : null;
                    String strategyLabel = pLabel.has("fineGrainedLabel") ? pLabel.get("fineGrainedLabel").asText() : null;
                    if (matchup == null || strategyLabel == null) continue;

                    JsonNode playerInfo = toonMap.get(pid);
                    if (playerInfo == null) continue;
                    String result = playerInfo.has("result") ? playerInfo.get("result").asText() : "";
                    boolean won = "Win".equals(result);

                    int playerId = Integer.parseInt(pid);
                    int enemyId = playerId == 1 ? 2 : 1;

                    JsonNode enemyPerspective = perspectives.get(String.valueOf(enemyId));
                    if (enemyPerspective == null) continue;
                    String enemyRawLabel = enemyPerspective.has("fineGrainedLabel")
                            ? enemyPerspective.get("fineGrainedLabel").asText() : null;
                    if (enemyRawLabel == null) continue;

                    String enemyMatchup = enemyPerspective.has("matchup")
                            ? enemyPerspective.get("matchup").asText() : matchup;
                    var ourMapped = ClassifierLabelMapper.mapToArchetypes(strategyLabel, matchup, GamePhase.EARLY);
                    var oppMapped = ClassifierLabelMapper.mapToArchetypes(enemyRawLabel, enemyMatchup, GamePhase.EARLY);

                    for (int checkpoint : new int[]{CHECKPOINT_3MIN, CHECKPOINT_5MIN}) {
                        if (totalLoops < checkpoint) continue;

                        var snapshot = extractGameStateAtCheckpoint(
                                events, playerId, enemyId, checkpoint);
                        if (snapshot == null) continue;

                        var ctx = PriorExportPipeline.discretizeContext(
                                snapshot.ourIncome, snapshot.enemyIncome,
                                snapshot.ourArmyCount, snapshot.enemyArmyCount,
                                snapshot.ourTechCount, snapshot.enemyTechCount);

                        for (var ourArch : ourMapped) {
                            for (var oppArch : oppMapped) {
                                String ourName = ClassifierLabelMapper.archLabel(ourArch);
                                String oppName = ClassifierLabelMapper.archLabel(oppArch);
                                var outcome = new ContextResponseOutcome(
                                        ourName, oppName, matchup, won,
                                        ctx.get("economyState"), ctx.get("armyState"), ctx.get("techState"));
                                byMatchup.computeIfAbsent(matchup, k -> new ArrayList<>()).add(outcome);
                                allOurByMatchup.computeIfAbsent(matchup, k -> new LinkedHashSet<>())
                                        .add(ourName);
                                totalOutcomes++;
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("Processed %d replays, %d outcomes across %d matchups%n",
                          totalReplays, totalOutcomes, byMatchup.size());

        for (var entry : byMatchup.entrySet()) {
            String matchup = entry.getKey();
            List<ContextResponseOutcome> outcomes = entry.getValue();
            Set<String> allOur = allOurByMatchup.get(matchup);

            var marginalOutcomes = outcomes.stream()
                    .map(o -> new PriorExportPipeline.ResponseOutcome(
                            o.ourStrategy(), o.opponentStrategy(), o.matchup(), o.won()))
                    .toList();
            var marginalPriors = PriorExportPipeline.learnResponsePriors(marginalOutcomes, allOur);

            var contextPriors = PriorExportPipeline.learnContextResponsePriors(
                    outcomes, allOur, marginalPriors, ALPHA);

            PriorExportPipeline.exportContextResponsePriorsToJson(
                    OUTPUT_DIR, matchup, contextPriors, ALPHA);

            long totalWins = outcomes.stream().filter(ContextResponseOutcome::won).count();
            int opponentCount = contextPriors.size();
            int totalCells = contextPriors.values().stream()
                    .mapToInt(m -> m.size()).sum();
            System.out.printf("  %s: %d outcomes, %d wins, %d opponents, %d context cells%n",
                              matchup, outcomes.size(), totalWins, opponentCount, totalCells);
        }

        assertFalse(byMatchup.isEmpty(), "Should have produced at least one matchup");
    }

    record GameStateSnapshot(
            double ourIncome, double enemyIncome,
            int ourArmyCount, int enemyArmyCount,
            long ourTechCount, long enemyTechCount) {}

    static GameStateSnapshot extractGameStateAtCheckpoint(
            JsonNode events, int playerId, int enemyId, int checkpointLoop) {

        double ourIncome = 0, enemyIncome = 0;
        boolean foundOurStats = false, foundEnemyStats = false;

        Map<Integer, String> aliveUnits = new LinkedHashMap<>();
        Map<Integer, Integer> unitOwner = new LinkedHashMap<>();

        for (JsonNode event : events) {
            int loop = event.get("loop").asInt();
            if (loop > checkpointLoop) break;

            String evtType = event.has("evtTypeName") ? event.get("evtTypeName").asText() : "";

            switch (evtType) {
                case "PlayerStats" -> {
                    int cpid = event.has("controlPlayerId") ? event.get("controlPlayerId").asInt() : 0;
                    JsonNode stats = event.get("stats");
                    if (stats == null) continue;
                    double minRate = stats.has("scoreValueMineralsCollectionRate")
                            ? stats.get("scoreValueMineralsCollectionRate").asDouble() / 1000.0 : 0;
                    double vesRate = stats.has("scoreValueVespeneCollectionRate")
                            ? stats.get("scoreValueVespeneCollectionRate").asDouble() / 1000.0 : 0;
                    double income = minRate + vesRate;
                    if (cpid == playerId) {
                        ourIncome = income;
                        foundOurStats = true;
                    } else if (cpid == enemyId) {
                        enemyIncome = income;
                        foundEnemyStats = true;
                    }
                }
                case "UnitBorn", "UnitInit" -> {
                    int cpid = event.has("controlPlayerId") ? event.get("controlPlayerId").asInt() : 0;
                    if (cpid != playerId && cpid != enemyId) continue;
                    String unitType = event.has("unitTypeName") ? event.get("unitTypeName").asText() : "";
                    int tag = event.has("unitTagIndex") ? event.get("unitTagIndex").asInt() : -1;
                    if (tag >= 0) {
                        aliveUnits.put(tag, unitType);
                        unitOwner.put(tag, cpid);
                    }
                }
                case "UnitDied" -> {
                    int tag = event.has("unitTagIndex") ? event.get("unitTagIndex").asInt() : -1;
                    aliveUnits.remove(tag);
                    unitOwner.remove(tag);
                }
            }
        }

        if (!foundOurStats && !foundEnemyStats) return null;

        int ourArmy = 0, enemyArmy = 0;
        long ourTech = 0, enemyTech = 0;
        for (var entry : aliveUnits.entrySet()) {
            int tag = entry.getKey();
            String unitType = entry.getValue();
            int owner = unitOwner.getOrDefault(tag, 0);
            if (owner == playerId) {
                ourArmy++;
                if (TECH_BUILDING_NAMES.contains(unitType)) ourTech++;
            } else if (owner == enemyId) {
                enemyArmy++;
                if (TECH_BUILDING_NAMES.contains(unitType)) enemyTech++;
            }
        }

        return new GameStateSnapshot(ourIncome, enemyIncome, ourArmy, enemyArmy, ourTech, enemyTech);
    }

    private static List<Path> listSubDirs(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static List<Path> listJsonFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")
                             && !p.toString().endsWith(".label.json"))
                    .sorted().toList();
        }
    }
}
