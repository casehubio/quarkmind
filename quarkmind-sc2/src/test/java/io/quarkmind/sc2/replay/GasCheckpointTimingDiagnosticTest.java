package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

/**
 * Investigates gas building completion timing at the 1-minute checkpoint.
 * Determines whether the 97% vespeneCurrent MAPE is a timing issue
 * (EM buildings complete late) or a PlayerStatsEvent reporting artifact.
 *
 * Refs #569, #534
 */
@Tag("diagnostic")
class GasCheckpointTimingDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void gasCheckpointTiming() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        int totalRuns = 0;
        int gtHasGasIncome = 0;
        int gtHasCompletedGasBuilding = 0;
        int gtGasIncomeNoBuilding = 0;
        int gtGasIncomeWithBuilding = 0;
        int emHasGasWorkers = 0;
        int emHasCompletedGasBuilding = 0;

        int gtGasRateTotal = 0;
        int gtVespeneTotalSum = 0;
        int emVespeneTotalSum = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    List<DivergenceReport.TickSnapshot> snapshots =
                        StandaloneEmulatedGameBaselineTest.runStandalone(
                            groundTruth, intents, TICKS_PER_MINUTE, race);

                    int tickIndex = TICKS_PER_MINUTE - 1;
                    if (tickIndex >= snapshots.size()) continue;
                    var snap = snapshots.get(tickIndex);
                    if (snap.groundTruthEconomy() == null) continue;

                    totalRuns++;

                    int gtGasRate = snap.groundTruthEconomy().vespeneCollectionRate();
                    int gtVespene = snap.groundTruthEconomy().vespeneCurrent();

                    boolean gtHasGas = gtGasRate > 0;
                    boolean gtHasGasBuil = snap.groundTruthBuildingsByType().entrySet().stream()
                        .anyMatch(e -> SC2Data.isGasBuilding(e.getKey()) && e.getValue() > 0);

                    boolean emHasGasBuil = snap.emulatedBuildingsByType().entrySet().stream()
                        .anyMatch(e -> SC2Data.isGasBuilding(e.getKey()) && e.getValue() > 0);

                    int emVespene = snap.emulatedEconomy() != null
                        ? snap.emulatedEconomy().vespeneCurrent() : 0;
                    int emGasRate = snap.emulatedEconomy() != null
                        ? snap.emulatedEconomy().vespeneCollectionRate() : 0;

                    if (gtHasGas) {
                        gtHasGasIncome++;
                        gtGasRateTotal += gtGasRate;
                    }
                    if (gtHasGasBuil) gtHasCompletedGasBuilding++;
                    if (gtHasGas && !gtHasGasBuil) gtGasIncomeNoBuilding++;
                    if (gtHasGas && gtHasGasBuil) gtGasIncomeWithBuilding++;
                    if (emGasRate > 0) emHasGasWorkers++;
                    if (emHasGasBuil) emHasCompletedGasBuilding++;
                    gtVespeneTotalSum += gtVespene;
                    emVespeneTotalSum += emVespene;

                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("\n=== Gas Checkpoint Timing at 1-min ===");
        System.out.printf("Total player runs: %d%n", totalRuns);
        System.out.printf("GT has gas income (rate > 0): %d (%.0f%%)%n",
            gtHasGasIncome, totalRuns > 0 ? gtHasGasIncome * 100.0 / totalRuns : 0);
        System.out.printf("GT has completed gas building: %d (%.0f%%)%n",
            gtHasCompletedGasBuilding, totalRuns > 0 ? gtHasCompletedGasBuilding * 100.0 / totalRuns : 0);
        System.out.printf("GT has gas income but NO completed gas building: %d — reporting artifact%n",
            gtGasIncomeNoBuilding);
        System.out.printf("GT has gas income AND completed gas building: %d — real gas mining%n",
            gtGasIncomeWithBuilding);
        System.out.printf("EM has completed gas building: %d (%.0f%%)%n",
            emHasCompletedGasBuilding, totalRuns > 0 ? emHasCompletedGasBuilding * 100.0 / totalRuns : 0);
        System.out.printf("EM has gas workers assigned (rate > 0): %d (%.0f%%)%n",
            emHasGasWorkers, totalRuns > 0 ? emHasGasWorkers * 100.0 / totalRuns : 0);
        System.out.printf("%nGT avg gas rate (when > 0): %.0f%n",
            gtHasGasIncome > 0 ? (double) gtGasRateTotal / gtHasGasIncome : 0);
        System.out.printf("GT total vespeneCurrent (sum): %d%n", gtVespeneTotalSum);
        System.out.printf("EM total vespeneCurrent (sum): %d%n", emVespeneTotalSum);
    }
}
