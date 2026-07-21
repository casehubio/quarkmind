package io.quarkmind.domain;

import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the 12.0 clustering radius in {@link ExpansionLocation#fromResources}
 * against real SC2 map resource layouts from IEM10 and AI Arena replays.
 *
 * NOT part of the regular test suite. Run explicitly:
 *   mvn test -Pbenchmark
 */
@Tag("benchmark")
class ExpansionLocationCalibrationTest {

    private static final Path AI_ARENA_DIR = Path.of("replays/aiarena_protoss");
    private static final Path IEM10_ZIP    = Path.of("replays/2016_IEM_10_Taipei.zip");

    @Test
    void iem10ExpansionCountsAreReasonable() throws IOException {
        List<IEM10JsonSimulatedGame> games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        assertThat(games).isNotEmpty();

        StringBuilder report = new StringBuilder();
        report.append("\n=== IEM10 Expansion Location Calibration (CLUSTER_RADIUS=12.0) ===\n");
        report.append(String.format("%-50s %8s %8s %8s %8s%n",
            "Replay", "Minerals", "Geysers", "Expns", "Start"));

        List<Integer> expansionCounts = new ArrayList<>();

        for (IEM10JsonSimulatedGame game : games) {
            GameState state = game.snapshot();
            List<Resource> minerals = state.mineralPatches();
            List<Resource> geysers = state.geysers();
            Point2d playerStart = findPlayerStart(state);

            if (minerals.isEmpty()) continue;

            List<ExpansionLocation> expansions =
                ExpansionLocation.fromResources(minerals, geysers, playerStart);
            expansionCounts.add(expansions.size());

            report.append(String.format("%-50s %8d %8d %8d  (%.0f,%.0f)%n",
                game.replayName().substring(0, Math.min(50, game.replayName().length())),
                minerals.size(), geysers.size(), expansions.size(),
                playerStart.x(), playerStart.y()));
        }

        report.append(String.format("%nGames with resources: %d / %d%n", expansionCounts.size(), games.size()));
        if (!expansionCounts.isEmpty()) {
            int min = expansionCounts.stream().mapToInt(i -> i).min().orElse(0);
            int max = expansionCounts.stream().mapToInt(i -> i).max().orElse(0);
            double avg = expansionCounts.stream().mapToInt(i -> i).average().orElse(0);
            report.append(String.format("Expansion count: min=%d, max=%d, avg=%.1f%n", min, max, avg));
        }

        System.out.println(report);

        assertThat(expansionCounts)
            .as("All IEM10 games should have resources extracted")
            .hasSizeGreaterThanOrEqualTo(25);
        assertThat(expansionCounts)
            .as("SC2 maps typically have 8-16 expansion locations")
            .allMatch(c -> c >= 4 && c <= 20);
    }

    @Test
    void aiArenaExpansionCountsAreReasonable() throws IOException {
        List<Path> replayFiles = Files.list(AI_ARENA_DIR)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted()
            .collect(Collectors.toList());

        StringBuilder report = new StringBuilder();
        report.append("\n=== AI Arena Expansion Location Calibration (CLUSTER_RADIUS=12.0) ===\n");
        report.append(String.format("%-50s %8s %8s %8s %8s%n",
            "Replay", "Minerals", "Geysers", "Expns", "Start"));

        List<Integer> expansionCounts = new ArrayList<>();
        int loaded = 0, skipped = 0;

        for (Path replay : replayFiles) {
            try {
                ReplaySimulatedGame game = new ReplaySimulatedGame(replay, 1);
                GameState state = game.snapshot();
                List<Resource> minerals = state.mineralPatches();
                List<Resource> geysers = state.geysers();
                Point2d playerStart = findPlayerStart(state);

                if (minerals.isEmpty()) { skipped++; continue; }

                List<ExpansionLocation> expansions =
                    ExpansionLocation.fromResources(minerals, geysers, playerStart);
                expansionCounts.add(expansions.size());
                loaded++;

                report.append(String.format("%-50s %8d %8d %8d  (%.0f,%.0f)%n",
                    replay.getFileName().toString(),
                    minerals.size(), geysers.size(), expansions.size(),
                    playerStart.x(), playerStart.y()));
            } catch (IllegalArgumentException e) {
                skipped++;
            }
        }

        report.append(String.format("%nLoaded: %d, Skipped: %d%n", loaded, skipped));
        if (!expansionCounts.isEmpty()) {
            int min = expansionCounts.stream().mapToInt(i -> i).min().orElse(0);
            int max = expansionCounts.stream().mapToInt(i -> i).max().orElse(0);
            double avg = expansionCounts.stream().mapToInt(i -> i).average().orElse(0);
            report.append(String.format("Expansion count: min=%d, max=%d, avg=%.1f%n", min, max, avg));
        }

        System.out.println(report);

        assertThat(expansionCounts)
            .as("At least some AI Arena replays should have resources")
            .isNotEmpty();
        assertThat(expansionCounts)
            .as("SC2 maps typically have 8-16 expansion locations")
            .allMatch(c -> c >= 4 && c <= 20);
    }

    private static Point2d findPlayerStart(GameState state) {
        return state.myBuildings().stream()
            .filter(b -> b.type() == BuildingType.NEXUS
                      || b.type() == BuildingType.COMMAND_CENTER
                      || b.type() == BuildingType.HATCHERY)
            .map(Building::position)
            .findFirst()
            .orElse(new Point2d(0, 0));
    }
}
