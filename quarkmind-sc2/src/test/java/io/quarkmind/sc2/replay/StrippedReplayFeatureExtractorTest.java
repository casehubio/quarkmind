package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrippedReplayFeatureExtractorTest {

    private static final Path LADDER_493 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    static boolean ladderReplaysExist() {
        if (!Files.isDirectory(LADDER_493)) return false;
        try (var stream = Files.list(LADDER_493)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void extractsBasicStructure() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        assertThat(gameJson).containsKey("trackerEvents");
        assertThat(gameJson).containsKey("ToonPlayerDescMap");
        assertThat(gameJson).containsKey("header");
        assertThat(gameJson).containsKey("metadata");

        @SuppressWarnings("unchecked")
        var header = (Map<String, Object>) gameJson.get("header");
        assertThat(header).containsKey("elapsedGameLoops");
        assertThat(((Number) header.get("elapsedGameLoops")).intValue()).isGreaterThan(0);

        @SuppressWarnings("unchecked")
        var playerMap = (Map<String, Object>) gameJson.get("ToonPlayerDescMap");
        assertThat(playerMap).containsKeys("1", "2");
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void extractsUnitBornEventsFromStrippedReplay() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");
        long unitBornCount = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .count();

        assertThat(unitBornCount).as("Must have synthetic UnitBorn events").isGreaterThan(0);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void extractsBuildingEvents() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");
        long unitInitCount = events.stream()
            .filter(e -> "UnitInit".equals(e.get("evtTypeName")))
            .count();
        long unitDoneCount = events.stream()
            .filter(e -> "UnitDone".equals(e.get("evtTypeName")))
            .count();

        assertThat(unitInitCount).as("Must have UnitInit events for buildings").isGreaterThan(0);
        assertThat(unitDoneCount).as("Must have UnitDone events for buildings").isGreaterThan(0);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void productionQueueDelaysSecondUnit() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        // Find the worker type for player 1
        @SuppressWarnings("unchecked")
        var playerMap = (Map<String, Object>) gameJson.get("ToonPlayerDescMap");
        @SuppressWarnings("unchecked")
        var p1 = (Map<String, Object>) playerMap.get("1");
        String race = (String) p1.get("race");
        String workerName = switch (race) {
            case "Protoss" -> "Probe";
            case "Terran" -> "SCV";
            case "Zerg" -> "Drone";
            default -> null;
        };
        if (workerName == null) return;

        List<Long> workerLoops = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName"))
                         && workerName.equals(e.get("unitTypeName"))
                         && Integer.valueOf(1).equals(e.get("controlPlayerId")))
            .map(e -> ((Number) e.get("loop")).longValue())
            .toList();

        if (workerLoops.size() >= 2) {
            assertThat(workerLoops.get(1) - workerLoops.get(0))
                .as("Second worker must be delayed by at least one train cycle")
                .isGreaterThanOrEqualTo(200);
        }
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void eventsAreSortedByLoop() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        for (int i = 1; i < events.size(); i++) {
            long prev = ((Number) events.get(i - 1).get("loop")).longValue();
            long curr = ((Number) events.get(i).get("loop")).longValue();
            assertThat(curr).as("Events must be sorted by loop (index %d)", i)
                .isGreaterThanOrEqualTo(prev);
        }
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void unitBornEventsUsePythonNames() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        var unitNames = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .map(e -> (String) e.get("unitTypeName"))
            .distinct()
            .toList();

        // Must not contain Java enum names with underscores
        for (String name : unitNames) {
            assertThat(name).as("Unit name '%s' must be PascalCase (Python format)", name)
                .doesNotContain("_");
        }
    }

    private Path firstReplay() throws Exception {
        try (var stream = Files.list(LADDER_493)) {
            return stream
                .filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted()
                .findFirst()
                .orElseThrow(() -> new AssertionError("No replays found"));
        }
    }
}
