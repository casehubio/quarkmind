package io.quarkmind.sc2.emulated;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

@Tag("diagnostic")
class GasHarvestCalibrationTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    private static final int TICKS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int CHECKPOINT_MINUTE = 5;
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * CHECKPOINT_MINUTE + 1;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void resetDefaults() {
        SimulatedWorker.resetGasHarvestDuration();
    }

    @Test
    @EnabledIf("oracleExists")
    void sweepGasHarvestDuration() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        System.out.printf("%nGas harvest duration calibration (standalone — no GT rate sync) — %d replays, %d-min checkpoint%n",
                          replays.size(), CHECKPOINT_MINUTE);
        System.out.printf("%-8s  %-8s  %10s  %10s  %10s  %12s%n",
                          "Loops", "Ticks", "GT Rate", "Em Rate", "VespMAPE%", "Ratio(EM/GT)");
        System.out.println("-".repeat(70));

        double bestMape  = Double.MAX_VALUE;
        double bestLoops = SimulatedWorker.GAS_HARVEST_DURATION_LOOPS_DEFAULT;

        for (int loops = 5; loops <= 50; loops += 5) {
            SimulatedWorker.setGasHarvestDurationLoops(loops);

            long sumGtRate = 0, sumEmRate = 0, sumAbsErr = 0;
            int  processed = 0;

            for (Path replayPath : replays) {
                Replay replay;
                try {
                    replay = RepParserEngine.parseReplay(replayPath,
                                                         EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                } catch (Exception e) {continue;}
                if (replay == null || replay.details == null || replay.trackerEvents == null) {continue;}
                if (replay.details.getPlayerList().length < 2) {continue;}

                for (int playerId = 1; playerId <= 2; playerId++) {
                    try {
                        io.quarkmind.sc2.mock.ReplaySimulatedGame groundTruth =
                                new io.quarkmind.sc2.mock.ReplaySimulatedGame(replayPath, playerId);
                        List<io.quarkmind.sc2.intent.TimedIntent> intents =
                                io.quarkmind.sc2.replay.TrackerIntentExtractor.extract(replayPath, playerId);
                        io.quarkmind.domain.Race race =
                                io.quarkmind.sc2.replay.TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                        EmulatedGame emulated = new EmulatedGame();
                        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
                        groundTruth.reset();
                        emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                        emulated.resetForHarness();

                        int cursor = 0;
                        for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
                            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;
                            emulated.tick();
                            groundTruth.tick();

                            emulated.processDeferredTrainIntents();
                            emulated.processDeferredResearchIntents();

                            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                                emulated.applyIntent(intents.get(cursor));
                                cursor++;
                            }
                        }

                        int                           tickIndex = CHECKPOINT_MINUTE * TICKS_PER_MINUTE - 1;
                        io.quarkmind.domain.GameState gt        = groundTruth.snapshot();
                        io.quarkmind.domain.GameState em        = emulated.snapshot();

                        if (gt.playerEconomy() != null && em.playerEconomy() != null) {
                            int gtRate = gt.playerEconomy().vespeneCollectionRate();
                            int emRate = em.playerEconomy().vespeneCollectionRate();
                            sumGtRate += gtRate;
                            sumEmRate += emRate;
                            sumAbsErr += Math.abs(gtRate - emRate);
                        }
                    } catch (Exception e) { /* skip */ }
                }
                processed++;
            }

            double mape  = sumGtRate > 0 ? sumAbsErr * 100.0 / sumGtRate : 0;
            double ratio = sumGtRate > 0 ? (double) sumEmRate / sumGtRate : 0;
            int    ticks = (int) Math.ceil((double) loops / SC2Data.LOOPS_PER_TICK);

            System.out.printf("%-8d  %-8d  %10d  %10d  %10.1f  %12.3f%n",
                              loops, ticks, sumGtRate, sumEmRate, mape, ratio);

            if (mape < bestMape) {
                bestMape  = mape;
                bestLoops = loops;
            }
        }

        SimulatedWorker.resetGasHarvestDuration();
        System.out.printf("%nBest: GAS_HARVEST_DURATION_LOOPS = %d (MAPE: %.1f%%)%n",
                          bestLoops, bestMape);
    }

    @Test
    @EnabledIf("oracleExists")
    void sweepGasCargo() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        System.out.printf("%nGas cargo calibration (1-tick harvest) — %d replays, %d-min checkpoint%n",
                          replays.size(), CHECKPOINT_MINUTE);
        System.out.printf("%-8s  %10s  %10s  %10s  %12s%n",
                          "Cargo", "GT Rate", "Em Rate", "VespMAPE%", "Ratio(EM/GT)");
        System.out.println("-".repeat(60));

        double bestMape  = Double.MAX_VALUE;
        double bestCargo = SimulatedWorker.GAS_CARGO_DEFAULT;
        SimulatedWorker.setGasHarvestDurationLoops(22);

        for (double cargo = 3.0; cargo <= 3.8; cargo += 0.1) {
            SimulatedWorker.GAS_CARGO = cargo;

            long sumGtRate = 0, sumEmRate = 0, sumAbsErr = 0;

            for (Path replayPath : replays) {
                Replay replay;
                try {
                    replay = RepParserEngine.parseReplay(replayPath,
                                                         EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                } catch (Exception e) {continue;}
                if (replay == null || replay.details == null || replay.trackerEvents == null) {continue;}
                if (replay.details.getPlayerList().length < 2) {continue;}

                for (int playerId = 1; playerId <= 2; playerId++) {
                    try {
                        io.quarkmind.sc2.mock.ReplaySimulatedGame groundTruth =
                                new io.quarkmind.sc2.mock.ReplaySimulatedGame(replayPath, playerId);
                        java.util.List<io.quarkmind.sc2.intent.TimedIntent> intents =
                                io.quarkmind.sc2.replay.TrackerIntentExtractor.extract(replayPath, playerId);
                        io.quarkmind.domain.Race race =
                                io.quarkmind.sc2.replay.TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                        EmulatedGame emulated = new EmulatedGame();
                        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
                        groundTruth.reset();
                        emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                        emulated.resetForHarness();

                        int cursor = 0;
                        for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
                            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;
                            emulated.tick();
                            groundTruth.tick();
                            emulated.processDeferredTrainIntents();
                            emulated.processDeferredResearchIntents();
                            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                                emulated.applyIntent(intents.get(cursor));
                                cursor++;
                            }
                        }

                        io.quarkmind.domain.GameState gt = groundTruth.snapshot();
                        io.quarkmind.domain.GameState em = emulated.snapshot();
                        if (gt.playerEconomy() != null && em.playerEconomy() != null) {
                            int gtRate = gt.playerEconomy().vespeneCollectionRate();
                            int emRate = em.playerEconomy().vespeneCollectionRate();
                            sumGtRate += gtRate;
                            sumEmRate += emRate;
                            sumAbsErr += Math.abs(gtRate - emRate);
                        }
                    } catch (Exception e) { /* skip */ }
                }
            }

            double mape  = sumGtRate > 0 ? sumAbsErr * 100.0 / sumGtRate : 0;
            double ratio = sumGtRate > 0 ? (double) sumEmRate / sumGtRate : 0;

            System.out.printf("%-8.1f  %10d  %10d  %10.1f  %12.3f%n",
                              cargo, sumGtRate, sumEmRate, mape, ratio);

            if (mape < bestMape) {
                bestMape  = mape;
                bestCargo = cargo;
            }
        }

        SimulatedWorker.GAS_CARGO = SimulatedWorker.GAS_CARGO_DEFAULT;
        SimulatedWorker.resetGasHarvestDuration();
        System.out.printf("%nBest: GAS_CARGO = %.1f (MAPE: %.1f%%)%n", bestCargo, bestMape);
    }

}
