package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayFeatureExtractorTest {

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
    void oracleReplayUsesTrackerPath() throws Exception {
        var extractor = new ReplayFeatureExtractor();
        Map<String, Object> result = extractor.extract(firstOracleReplay());

        assertThat(result).containsKey("trackerEvents");
        assertThat(result).containsKey("gameCommands");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
            (List<Map<String, Object>>) result.get("trackerEvents");
        assertThat(events).isNotEmpty();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands =
            (List<Map<String, Object>>) result.get("gameCommands");
        assertThat(commands).isNotEmpty();
    }

    @Test
    @EnabledIf("oracleExists")
    void gameCommandsHaveTypeAndLoop() throws Exception {
        var extractor = new ReplayFeatureExtractor();
        Map<String, Object> result = extractor.extract(firstOracleReplay());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands =
            (List<Map<String, Object>>) result.get("gameCommands");

        for (Map<String, Object> cmd : commands) {
            assertThat(cmd).containsKey("type");
            assertThat(cmd).containsKey("loop");
            assertThat(cmd).containsKey("playerId");
        }
    }
}
