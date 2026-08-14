package io.quarkmind.sc2.mock;

import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.replay.DivergenceReport;
import io.quarkmind.sc2.replay.ReplayValidationHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full IEM10 multi-game divergence report — run with: mvn test -Preport
 *
 * Iterates all 30 IEM10 games, extracts intents, runs validation harness,
 * collects per-game and per-matchup aggregate statistics.
 */
@Tag("report")
class IEM10MultiGameValidationTest {

    private static final Path IEM10_ZIP = Path.of("replays/2016_IEM_10_Taipei.zip");

    @Test
    void fullIEM10DatasetValidationReport() throws IOException {
        List<IEM10JsonSimulatedGame> games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);

        // Group by matchup
        Map<String, List<GameValidationResult>> resultsByMatchup = new TreeMap<>();
        resultsByMatchup.put("PvT", new ArrayList<>());
        resultsByMatchup.put("PvZ", new ArrayList<>());
        resultsByMatchup.put("PvP", new ArrayList<>());

        List<String> gamesWithEmptyIntents = new ArrayList<>();

        System.out.println("\n=== IEM10 Multi-Game Validation Report ===\n");
        System.out.println(String.format("%-40s  %-6s  %-10s  %-10s  %-8s",
            "Game", "Match", "Mean ΔUnits", "Max ΔUnits", "Intents"));
        System.out.println("-".repeat(85));

        for (IEM10JsonSimulatedGame game : games) {
            String matchup = game.matchup();
            String gameName = game.replayName();
            if (gameName.length() > 40) {
                gameName = "..." + gameName.substring(gameName.length() - 37);
            }

            // Extract intents and run harness
            List<TimedIntent> intents = IEM10CommandExtractor.extract(game);

            if (intents.isEmpty()) {
                gamesWithEmptyIntents.add(game.replayName());
            }

            // Run via ReplayValidationHarness — reset() is called inside run()
            DivergenceReport report = ReplayValidationHarness.run(game, intents, Integer.MAX_VALUE);

            // Calculate per-game stats
            double meanUnitDelta = report.ticks().stream()
                .mapToInt(DivergenceReport.TickSnapshot::unitDelta)
                .average()
                .orElse(0.0);
            int maxUnitDelta = report.summary().maxUnitDelta();

            GameValidationResult result = new GameValidationResult(
                game.replayName(),
                matchup,
                meanUnitDelta,
                maxUnitDelta,
                intents.size(),
                report
            );

            resultsByMatchup.computeIfAbsent(matchup, k -> new ArrayList<>()).add(result);

            // Print per-game line
            System.out.println(String.format("%-40s  %-6s  %10.2f  %10d  %8d",
                gameName, matchup, meanUnitDelta, maxUnitDelta, intents.size()));
        }

        System.out.println();

        // Aggregate stats by matchup
        System.out.println("\n=== Per-Matchup Aggregates ===\n");
        System.out.println(String.format("%-8s  %-12s  %-12s  %-10s  %-8s",
            "Matchup", "Avg Mean ΔU", "Avg Max ΔU", "Games", "Accurate"));
        System.out.println("-".repeat(60));

        int totalAccurate = 0;
        int totalGames = 0;

        for (String matchup : new String[]{"PvT", "PvZ", "PvP"}) {
            List<GameValidationResult> results = resultsByMatchup.get(matchup);
            if (results.isEmpty()) continue;

            double avgMeanDelta = results.stream()
                .mapToDouble(r -> r.meanUnitDelta)
                .average()
                .orElse(0.0);

            double avgMaxDelta = results.stream()
                .mapToDouble(r -> r.maxUnitDelta)
                .average()
                .orElse(0.0);

            long accurateCount = results.stream()
                .filter(r -> r.report.summary().economicallyAccurate())
                .count();

            totalAccurate += accurateCount;
            totalGames += results.size();

            System.out.println(String.format("%-8s  %12.2f  %12.2f  %10d  %8d/%d",
                matchup, avgMeanDelta, avgMaxDelta, results.size(),
                (int) accurateCount, results.size()));
        }

        System.out.println();
        System.out.println(String.format("Total: %d games, %d economically accurate", totalGames, totalAccurate));

        if (!gamesWithEmptyIntents.isEmpty()) {
            System.out.println("\nWarning: " + gamesWithEmptyIntents.size() + " games had no extracted intents:");
            gamesWithEmptyIntents.forEach(name -> System.out.println("  - " + name));
        }

        System.out.println();

        // Sanity assertion: at least 28 of 30 games must produce non-empty intent streams
        assertThat(games.size() - gamesWithEmptyIntents.size())
            .as("Games with extracted intents").isGreaterThanOrEqualTo(28);
    }

    /**
     * Per-game validation result container.
     */
    private record GameValidationResult(
        String replayName,
        String matchup,
        double meanUnitDelta,
        int maxUnitDelta,
        int intentCount,
        DivergenceReport report
    ) {}
}
