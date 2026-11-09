package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EconomyConfig;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

@Tag("diagnostic")
class RateCadenceDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final float GAS_PER_WORKER_PER_MIN = 54.0f;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void measurePlayerStatsEventCadence() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        List<Integer> allDeltas = new ArrayList<>();
        int replayCount = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.trackerEvents == null) continue;

            List<Integer> playerStatsLoops = new ArrayList<>();
            for (var event : replay.trackerEvents.events) {
                if (event instanceof IPlayerStatsEvent && event.getPlayerId() != null) {
                    if (event.getPlayerId() == 1) {
                        playerStatsLoops.add(event.getLoop());
                    }
                }
            }

            for (int i = 1; i < playerStatsLoops.size(); i++) {
                allDeltas.add(playerStatsLoops.get(i) - playerStatsLoops.get(i - 1));
            }
            replayCount++;
        }

        allDeltas.sort(Integer::compareTo);
        double mean = allDeltas.stream().mapToInt(i -> i).average().orElse(0);
        int median = allDeltas.isEmpty() ? 0 : allDeltas.get(allDeltas.size() / 2);
        int min = allDeltas.isEmpty() ? 0 : allDeltas.getFirst();
        int max = allDeltas.isEmpty() ? 0 : allDeltas.getLast();

        System.out.println("=== PlayerStatsEvent Cadence ===\n");
        System.out.printf("Replays: %d, Intervals: %d%n", replayCount, allDeltas.size());
        System.out.printf("Mean:   %.2f loops (%.3f seconds)%n", mean, mean / SC2Data.GAME_LOOPS_PER_SECOND);
        System.out.printf("Median: %d loops%n", median);
        System.out.printf("Min:    %d loops%n", min);
        System.out.printf("Max:    %d loops%n", max);

        long exactly160 = allDeltas.stream().filter(d -> d == 160).count();
        System.out.printf("Exactly 160: %d / %d (%.1f%%)%n",
            exactly160, allDeltas.size(), exactly160 * 100.0 / allDeltas.size());
    }

    @Test
    @EnabledIf("oracleExists")
    void compareCadenceMapeContribution() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        int[] windows = {154, 160, 176};
        double[][][] mineralAccum = new double[windows.length][CHECKPOINTS_MIN.length][3];
        double[][][] gasAccum = new double[windows.length][CHECKPOINTS_MIN.length][3];
        int totalPlayerRuns = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);

                    for (int w = 0; w < windows.length; w++) {
                        ReplaySimulatedGame gt = new ReplaySimulatedGame(replayPath, playerId);
                        List<DivergenceReport.TickSnapshot> snapshots =
                            runWithRateInterval(gt, intents, TICK_LIMIT, race, windows[w]);

                        for (int c = 0; c < CHECKPOINTS_MIN.length; c++) {
                            int tickIndex = CHECKPOINTS_MIN[c] * TICKS_PER_MINUTE - 1;
                            if (tickIndex >= snapshots.size()) continue;
                            var snap = snapshots.get(tickIndex);
                            if (snap.groundTruthEconomy() == null || snap.emulatedEconomy() == null) continue;
                            PlayerEconomyStats gtEcon = snap.groundTruthEconomy();
                            PlayerEconomyStats emEcon = snap.emulatedEconomy();

                            mineralAccum[w][c][0] += gtEcon.mineralsCollectionRate();
                            mineralAccum[w][c][1] += emEcon.mineralsCollectionRate();
                            mineralAccum[w][c][2] += Math.abs(
                                (double) gtEcon.mineralsCollectionRate() - emEcon.mineralsCollectionRate());

                            gasAccum[w][c][0] += gtEcon.vespeneCollectionRate();
                            gasAccum[w][c][1] += emEcon.vespeneCollectionRate();
                            gasAccum[w][c][2] += Math.abs(
                                (double) gtEcon.vespeneCollectionRate() - emEcon.vespeneCollectionRate());
                        }
                    }
                    totalPlayerRuns++;
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.printf("%n=== Rate Cadence MAPE Comparison (%d player runs) ===%n%n", totalPlayerRuns);
        System.out.println("| Checkpoint | Field | 154-loop MAPE | 160-loop MAPE | 176-loop MAPE | Delta (154->160) |");
        System.out.println("|---|---|---|---|---|---|");
        for (int c = 0; c < CHECKPOINTS_MIN.length; c++) {
            double[] mineralMapes = new double[windows.length];
            double[] gasMapes = new double[windows.length];
            for (int w = 0; w < windows.length; w++) {
                mineralMapes[w] = mineralAccum[w][c][0] != 0
                    ? mineralAccum[w][c][2] / Math.abs(mineralAccum[w][c][0]) * 100 : 0;
                gasMapes[w] = gasAccum[w][c][0] != 0
                    ? gasAccum[w][c][2] / Math.abs(gasAccum[w][c][0]) * 100 : 0;
            }
            System.out.printf("| %d-min | mineralsCollectionRate | %.1f%% | %.1f%% | %.1f%% | %+.1f%% |%n",
                CHECKPOINTS_MIN[c], mineralMapes[0], mineralMapes[1], mineralMapes[2],
                mineralMapes[1] - mineralMapes[0]);
            System.out.printf("| %d-min | vespeneCollectionRate | %.1f%% | %.1f%% | %.1f%% | %+.1f%% |%n",
                CHECKPOINTS_MIN[c], gasMapes[0], gasMapes[1], gasMapes[2],
                gasMapes[1] - gasMapes[0]);
        }
    }

    private static List<DivergenceReport.TickSnapshot> runWithRateInterval(
            ReplaySimulatedGame groundTruth, List<TimedIntent> intents,
            int tickLimit, Race race, int rateIntervalLoops) {

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
        emulated.setEconomyConfig(new EconomyConfig(rateIntervalLoops));
        groundTruth.reset();
        emulated.setMineralPatchResources(groundTruth.getMineralPatches());
        emulated.resetForHarness();

        int cursor = 0;
        List<DivergenceReport.TickSnapshot> snapshots = new ArrayList<>(Math.min(tickLimit, 10000));

        for (int tick = 0; tick < tickLimit && !groundTruth.isComplete(); tick++) {
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
            int estimatedGasWorkers = gtGasRate > 0
                ? Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN)) : 0;
            emulated.syncTotalGasWorkers(estimatedGasWorkers);

            GameState em = emulated.snapshot();

            snapshots.add(new DivergenceReport.TickSnapshot(
                tick,
                gt.myUnits().size(), em.myUnits().size(),
                gt.myBuildings().size(), em.myBuildings().size(),
                gt.minerals(), em.minerals(),
                gt.vespene(), em.vespene(),
                new EnumMap<>(UnitType.class), new EnumMap<>(UnitType.class),
                new EnumMap<>(BuildingType.class), new EnumMap<>(BuildingType.class),
                gt.playerUpgrades(), em.playerUpgrades(),
                gt.playerEconomy(), em.playerEconomy()));
        }
        return snapshots;
    }
}
