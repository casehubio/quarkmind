package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrippedReplayEconomyTest {

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
    void emitsPlayerStatsEvents() throws Exception {
        Path replay = firstReplay();
        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        long statsCount = events.stream()
            .filter(e -> "PlayerStats".equals(e.get("evtTypeName")))
            .count();

        assertThat(statsCount).as("Must have PlayerStats events").isGreaterThan(0);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void playerStatsEmittedEvery160Loops() throws Exception {
        Path replay = firstReplay();
        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        List<Long> statsLoops = events.stream()
            .filter(e -> "PlayerStats".equals(e.get("evtTypeName"))
                         && Integer.valueOf(1).equals(e.get("controlPlayerId")))
            .map(e -> ((Number) e.get("loop")).longValue())
            .toList();

        assertThat(statsLoops).as("PlayerStats loops for player 1").isNotEmpty();

        // Each stats event should be at a multiple of 160
        for (long loop : statsLoops) {
            assertThat(loop % 160).as("PlayerStats at loop %d should be at 160-loop interval", loop)
                .isEqualTo(0);
        }
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void playerStatsContainAll13Keys() throws Exception {
        Path replay = firstReplay();
        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        var firstStats = events.stream()
            .filter(e -> "PlayerStats".equals(e.get("evtTypeName")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No PlayerStats events"));

        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) firstStats.get("stats");
        assertThat(stats).containsKeys(
            "scoreValueMineralsCurrent", "scoreValueVespeneCurrent",
            "scoreValueMineralsCollectionRate", "scoreValueVespeneCollectionRate",
            "scoreValueFoodMade", "scoreValueFoodUsed",
            "scoreValueWorkersActiveCount",
            "scoreValueMineralsUsedCurrentArmy", "scoreValueMineralsUsedCurrentEconomy",
            "scoreValueMineralsUsedCurrentTechnology",
            "scoreValueVespeneUsedCurrentArmy", "scoreValueVespeneUsedCurrentEconomy",
            "scoreValueVespeneUsedCurrentTechnology"
        );
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void foodMadeIncludesInitialSupply() throws Exception {
        Path replay = firstReplay();
        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        var firstStats = events.stream()
            .filter(e -> "PlayerStats".equals(e.get("evtTypeName"))
                         && Integer.valueOf(1).equals(e.get("controlPlayerId")))
            .findFirst()
            .orElseThrow();

        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) firstStats.get("stats");
        double foodMade = ((Number) stats.get("scoreValueFoodMade")).doubleValue();

        // Initial supply is 15 (all races) — food values are ×4096 in SC2
        assertThat(foodMade).as("foodMade must include initial supply (15 × 4096)")
            .isGreaterThanOrEqualTo(15 * 4096);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void workersActiveStartsAt12() throws Exception {
        Path replay = firstReplay();
        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        var firstStats = events.stream()
            .filter(e -> "PlayerStats".equals(e.get("evtTypeName"))
                         && Integer.valueOf(1).equals(e.get("controlPlayerId")))
            .findFirst()
            .orElseThrow();

        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) firstStats.get("stats");
        int workers = ((Number) stats.get("scoreValueWorkersActiveCount")).intValue();

        assertThat(workers).as("Workers should start at 12").isEqualTo(12);
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
