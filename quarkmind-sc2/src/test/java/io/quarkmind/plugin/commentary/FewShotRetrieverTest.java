package io.quarkmind.plugin.commentary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FewShotRetrieverTest {

    @Test
    void retrievesByPhaseAndEventType(@TempDir Path tempDir) throws IOException {
        Path fewShotDir = tempDir.resolve("few-shot");
        Files.createDirectories(fewShotDir);
        Files.writeString(fewShotDir.resolve("mid_game_battle.json"), """
            {
              "phase": "mid_game",
              "event_type": "battle",
              "example_count": 2,
              "examples": [
                {"id": "ex1", "quality_score": 0.9, "matchup": "TvP",
                 "game_state_summary": "Player: Terran; Army: 8 Marine, 3 Marauder",
                 "commentary": "Big push coming in from the Terran army"},
                {"id": "ex2", "quality_score": 0.8, "matchup": "PvZ",
                 "game_state_summary": "Player: Protoss; Army: 6 Stalker",
                 "commentary": "Stalker force moving across the map"}
              ]
            }
            """);

        FewShotRetriever retriever = FewShotRetriever.fromDirectory(fewShotDir);
        List<FewShotExample> results = retriever.retrieve("mid_game", "battle", null, 3);

        assertEquals(2, results.size());
        assertEquals("ex1", results.get(0).id());
    }

    @Test
    void returnsEmptyForUnknownCell(@TempDir Path tempDir) {
        FewShotRetriever retriever = FewShotRetriever.fromDirectory(tempDir);
        List<FewShotExample> results = retriever.retrieve("endgame", "battle", null, 3);

        assertTrue(results.isEmpty());
    }

    @Test
    void filtersAndSortsByMatchup(@TempDir Path tempDir) throws IOException {
        Path fewShotDir = tempDir.resolve("few-shot");
        Files.createDirectories(fewShotDir);
        Files.writeString(fewShotDir.resolve("mid_game_battle.json"), """
            {
              "phase": "mid_game", "event_type": "battle", "example_count": 3,
              "examples": [
                {"id": "ex1", "quality_score": 0.9, "matchup": "TvP",
                 "game_state_summary": "...", "commentary": "..."},
                {"id": "ex2", "quality_score": 0.85, "matchup": "PvZ",
                 "game_state_summary": "...", "commentary": "..."},
                {"id": "ex3", "quality_score": 0.8, "matchup": "TvP",
                 "game_state_summary": "...", "commentary": "..."}
              ]
            }
            """);

        FewShotRetriever retriever = FewShotRetriever.fromDirectory(fewShotDir);
        List<FewShotExample> results = retriever.retrieve("mid_game", "battle", "TvP", 2);

        assertEquals(2, results.size());
        assertEquals("TvP", results.get(0).matchup());
    }

    @Test
    void respectsLimit(@TempDir Path tempDir) throws IOException {
        Path fewShotDir = tempDir.resolve("few-shot");
        Files.createDirectories(fewShotDir);
        Files.writeString(fewShotDir.resolve("mid_game_battle.json"), """
            {
              "phase": "mid_game", "event_type": "battle", "example_count": 3,
              "examples": [
                {"id": "ex1", "quality_score": 0.9, "matchup": "TvP",
                 "game_state_summary": "...", "commentary": "..."},
                {"id": "ex2", "quality_score": 0.85, "matchup": "PvZ",
                 "game_state_summary": "...", "commentary": "..."},
                {"id": "ex3", "quality_score": 0.8, "matchup": "TvP",
                 "game_state_summary": "...", "commentary": "..."}
              ]
            }
            """);

        FewShotRetriever retriever = FewShotRetriever.fromDirectory(fewShotDir);
        List<FewShotExample> results = retriever.retrieve("mid_game", "battle", null, 1);

        assertEquals(1, results.size());
    }
}
