package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
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

@Tag("diagnostic")
class WorkerDistributionDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final float GAS_PER_WORKER_PER_MIN = 38.0f;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void perBaseWorkerDistribution() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        int[]   checkpoints   = {3, 5, 7};
        int     totalRuns     = 0;
        int[][] perCheckpoint = new int[checkpoints.length][4]; // [gtMain, emMain, gtExp, emExp]

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            String mapName = ReplayValidationHarness.resolveMapName(replayPath);

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent>   intents     = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race                race        = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    io.quarkmind.sc2.emulated.EmulatedGame emulated = new io.quarkmind.sc2.emulated.EmulatedGame();
                    emulated.setPlayerRaceModel(io.quarkmind.sc2.emulated.RaceModelFactory.forRace(race));
                    ReplayValidationHarness.loadTerrainFromCache(emulated, mapName);
                    groundTruth.reset();
                    emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                    emulated.resetForHarness();

                    int cursor = 0;
                    totalRuns++;

                    for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
                        long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

                        emulated.tick();
                        groundTruth.tick();

                        GameState gt = groundTruth.snapshot();

                        emulated.processDeferredTrainIntents();
                        emulated.processDeferredResearchIntents();

                        while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                            emulated.applyIntent(intents.get(cursor));
                            cursor++;
                        }

                        int gtGasRate = gt.playerEconomy().vespeneCollectionRate();
                        if (gtGasRate > 0) {
                            int estimatedGasWorkers = Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN));
                            emulated.syncTotalGasWorkers(estimatedGasWorkers);
                        }

                        for (int ci = 0; ci < checkpoints.length; ci++) {
                            int cpTick = checkpoints[ci] * TICKS_PER_MINUTE - 1;
                            if (tick != cpTick) {continue;}

                            // GT: position-based (all workers)
                            int[] gtPerBase = io.quarkmind.sc2.emulated.EmulatedGame.countWorkersPerBase(race,
                                                                                                         gt.myBuildings(), gt.myUnits());
                            // EM: internal base assignment (mineral workers only — the meaningful metric)
                            int[] emPerBase = emulated.mineralWorkersPerBase();

                            int gtMain = gtPerBase.length > 0 ? gtPerBase[0] : 0;
                            int emMain = emPerBase.length > 0 ? emPerBase[0] : 0;
                            int gtExp  = 0;
                            int emExp  = 0;
                            for (int b = 1; b < gtPerBase.length; b++) {gtExp += gtPerBase[b];}
                            for (int b = 1; b < emPerBase.length; b++) {emExp += emPerBase[b];}

                            perCheckpoint[ci][0] += gtMain;
                            perCheckpoint[ci][1] += emMain;
                            perCheckpoint[ci][2] += gtExp;
                            perCheckpoint[ci][3] += emExp;
                        }
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        }

        System.out.println("\n=== Worker Distribution Diagnostic (#757) ===");
        System.out.printf("Total player runs: %d%n", totalRuns);
        System.out.println("GT=all workers by position, EM=mineral workers by internal baseIndex:");
        System.out.println("  CP  | GT main | EM main | GT exp | EM exp | Main Δ | Exp Δ");
        System.out.println("  ----|---------|---------|--------|--------|--------|------");
        for (int ci = 0; ci < checkpoints.length; ci++) {
            int    gtM       = perCheckpoint[ci][0];
            int    emM       = perCheckpoint[ci][1];
            int    gtE       = perCheckpoint[ci][2];
            int    emE       = perCheckpoint[ci][3];
            double mainDelta = gtM > 0 ? (emM - gtM) * 100.0 / gtM : 0;
            double expDelta  = gtE > 0 ? (emE - gtE) * 100.0 / gtE : 0;
            System.out.printf("  %d   | %7d | %7d | %6d | %6d | %+5.1f%% | %+5.1f%%%n",
                              checkpoints[ci], gtM, emM, gtE, emE, mainDelta, expDelta);
        }
        System.out.println("\nNote: GT includes gas workers (position-based), EM is mineral-only (internal).");
        System.out.println("Expect EM main < GT main by the gas worker count. Focus on expansion Δ direction.");
    }
}
