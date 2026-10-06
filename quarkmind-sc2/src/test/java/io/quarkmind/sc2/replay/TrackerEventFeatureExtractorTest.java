package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrackerEventFeatureExtractorTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var s = Files.list(ORACLE_DIR)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    private Path firstOracleReplay() throws Exception {
        try (var s = Files.list(ORACLE_DIR)) {
            return s.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().findFirst().orElseThrow();
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void extractProducesAllEventTypes() throws Exception {
        Replay replay = RepParserEngine.parseReplay(firstOracleReplay(),
            EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));

        var extractor = new TrackerEventFeatureExtractor();
        Map<String, Object> result = extractor.extract(replay);

        assertThat(result).containsKey("ToonPlayerDescMap");
        assertThat(result).containsKey("trackerEvents");
        assertThat(result).containsKey("header");
        assertThat(result).containsKey("metadata");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
            (List<Map<String, Object>>) result.get("trackerEvents");
        assertThat(events).isNotEmpty();

        Set<String> eventTypes = new java.util.HashSet<>();
        for (Map<String, Object> e : events) {
            eventTypes.add((String) e.get("evtTypeName"));
        }
        assertThat(eventTypes).contains("UnitBorn", "UnitInit", "UnitDone",
            "UnitDied", "Upgrade", "PlayerStats");
    }

    @Test
    @EnabledIf("oracleExists")
    void playerStatsScaledCorrectly() throws Exception {
        Replay replay = RepParserEngine.parseReplay(firstOracleReplay(),
            EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));

        var extractor = new TrackerEventFeatureExtractor();
        Map<String, Object> result = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
            (List<Map<String, Object>>) result.get("trackerEvents");

        Map<String, Object> firstStats = events.stream()
            .filter(e -> "PlayerStats".equals(e.get("evtTypeName")))
            .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) firstStats.get("stats");
        assertThat(stats).containsKey("scoreValueMineralsCurrent");
        assertThat(stats).containsKey("scoreValueFoodMade");
        assertThat(stats).hasSize(13);
    }

    @Test
    @EnabledIf("oracleExists")
    void eventsAreSortedByLoop() throws Exception {
        Replay replay = RepParserEngine.parseReplay(firstOracleReplay(),
            EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));

        var extractor = new TrackerEventFeatureExtractor();
        Map<String, Object> result = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
            (List<Map<String, Object>>) result.get("trackerEvents");

        long prevLoop = -1;
        for (Map<String, Object> e : events) {
            long loop = ((Number) e.get("loop")).longValue();
            assertThat(loop).isGreaterThanOrEqualTo(prevLoop);
            prevLoop = loop;
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void unitEventsHaveRequiredFields() throws Exception {
        Replay replay = RepParserEngine.parseReplay(firstOracleReplay(),
            EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));

        var extractor = new TrackerEventFeatureExtractor();
        Map<String, Object> result = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
            (List<Map<String, Object>>) result.get("trackerEvents");

        Map<String, Object> firstBorn = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .findFirst().orElseThrow();

        assertThat(firstBorn).containsKeys("evtTypeName", "loop",
            "controlPlayerId", "unitTypeName", "unitTagIndex", "unitTagRecycle");
    }
}
