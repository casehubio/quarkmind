package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Tag("diagnostic")
class GasWorkerEstimationDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final float GAS_PER_WORKER_PER_MIN = 38.0f;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void gasWorkerEstimationAccuracy() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        int[] checkpoints = {1, 3, 5, 7};
        int totalRuns = 0;
        long[][] accum = new long[checkpoints.length][4]; // [gtGasWorkers, estGasWorkers, overCount, underCount]

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
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    groundTruth.reset();
                    totalRuns++;

                    for (int tick = 0; tick < TICKS_PER_MINUTE * 8 && !groundTruth.isComplete(); tick++) {
                        groundTruth.tick();
                        GameState gt = groundTruth.snapshot();

                        for (int ci = 0; ci < checkpoints.length; ci++) {
                            int cpTick = checkpoints[ci] * TICKS_PER_MINUTE - 1;
                            if (tick != cpTick) continue;

                            Map<String, Integer> gasPerBuilding =
                                EmulatedGame.countGasWorkersPerBuilding(race, gt);
                            int gtGasWorkers = gasPerBuilding.values().stream()
                                .mapToInt(Integer::intValue).sum();

                            int gtGasRate = gt.playerEconomy().vespeneCollectionRate();
                            int estimated = gtGasRate > 0
                                ? Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN))
                                : 0;

                            accum[ci][0] += gtGasWorkers;
                            accum[ci][1] += estimated;
                            if (estimated > gtGasWorkers) accum[ci][2]++;
                            if (estimated < gtGasWorkers) accum[ci][3]++;
                        }
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        }

        System.out.println("\n=== Gas Worker Estimation Diagnostic (#758) ===");
        System.out.printf("Total player runs: %d, GAS_PER_WORKER_PER_MIN=%.1f%n%n", totalRuns, GAS_PER_WORKER_PER_MIN);
        System.out.println("  CP  | GT gas | Est gas | Δ      | Over | Under | Over%");
        System.out.println("  ----|--------|---------|--------|------|-------|------");
        for (int ci = 0; ci < checkpoints.length; ci++) {
            long gt = accum[ci][0];
            long est = accum[ci][1];
            long over = accum[ci][2];
            long under = accum[ci][3];
            double delta = gt > 0 ? (est - gt) * 100.0 / gt : 0;
            double overPct = (over + under) > 0 ? over * 100.0 / (over + under) : 0;
            System.out.printf("  %d   | %6d | %7d | %+5.1f%% | %4d | %5d | %4.0f%%%n",
                checkpoints[ci], gt, est, delta, over, under, overPct);
        }
    }
}
