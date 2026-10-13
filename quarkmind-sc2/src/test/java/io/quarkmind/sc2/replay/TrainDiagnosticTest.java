package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.factory.RepContent;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Tag("diagnostic")
class TrainDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    private static final Set<UnitType> KEY_TYPES = EnumSet.of(
        UnitType.MARINE, UnitType.HELLION, UnitType.WIDOW_MINE,
        UnitType.MEDIVAC, UnitType.PROBE, UnitType.PHOENIX,
        UnitType.STALKER, UnitType.REAPER);

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void trainIntentDiagnostic() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        long fiveMinLoops = (long)(5 * 60 * SC2Data.GAME_LOOPS_PER_SECOND);

        Map<UnitType, long[]> intentCountsAll = new EnumMap<>(UnitType.class);
        Map<UnitType, long[]> intentCountsBefore5 = new EnumMap<>(UnitType.class);
        Map<UnitType, long[]> diagAgg = new EnumMap<>(UnitType.class);
        int playerRuns = 0;

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

                    for (TimedIntent ti : intents) {
                        if (ti.intent() instanceof TrainIntent t) {
                            intentCountsAll.computeIfAbsent(t.unitType(), k -> new long[1])[0]++;
                            if (ti.loop() < fiveMinLoops) {
                                intentCountsBefore5.computeIfAbsent(t.unitType(), k -> new long[1])[0]++;
                            }
                        }
                    }

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));

                    ReplayValidationHarness.run(groundTruth, intents, TICK_LIMIT, emulated, race);

                    for (var entry : emulated.trackerTrainDiagnostics().entrySet()) {
                        long[] agg = diagAgg.computeIfAbsent(entry.getKey(), k -> new long[7]);
                        int[] slots = entry.getValue();
                        for (int i = 0; i < 7; i++) {
                            agg[i] += slots[i];
                        }
                    }
                    playerRuns++;
                } catch (Exception e) { /* skip failed replays */ }
            }
        }

        System.out.printf("%n=== Train Intent Diagnostic (%d player runs, 5min cutoff=%d loops) ===%n%n",
            playerRuns, fiveMinLoops);
        System.out.println("| Type | AllIntents | Before5min | Received | Trained | Bldg404 | QueueFull | Overflow |");
        System.out.println("|---|---|---|---|---|---|---|---|");

        for (UnitType ut : KEY_TYPES) {
            long all = intentCountsAll.getOrDefault(ut, new long[1])[0];
            long before5 = intentCountsBefore5.getOrDefault(ut, new long[1])[0];
            long[] d = diagAgg.getOrDefault(ut, new long[7]);
            System.out.printf("| %s | %d | %d | %d | %d | %d | %d | %d |%n",
                ut, all, before5, d[0], d[1], d[2], d[5], d[6]);
        }

        System.out.println();
        System.out.println("### All unit types (totals)");
        System.out.println("| Type | AllIntents | Before5min | Received | Trained | Bldg404 | QueueFull | Overflow |");
        System.out.println("|---|---|---|---|---|---|---|---|");

        long allIntents = 0, allBefore5 = 0;
        long[] allDiag = new long[7];
        for (var entry : intentCountsAll.entrySet()) allIntents += entry.getValue()[0];
        for (var entry : intentCountsBefore5.entrySet()) allBefore5 += entry.getValue()[0];
        for (var entry : diagAgg.entrySet()) {
            long[] d = entry.getValue();
            for (int i = 0; i < 7; i++) allDiag[i] += d[i];
        }
        System.out.printf("| ALL | %d | %d | %d | %d | %d | %d | %d |%n",
            allIntents, allBefore5, allDiag[0], allDiag[1], allDiag[2], allDiag[5], allDiag[6]);

        System.out.printf("%nTrained: %d / %d received (%.1f%%)%n", allDiag[1], allDiag[0],
            allDiag[0] > 0 ? allDiag[1] * 100.0 / allDiag[0] : 0);
        System.out.printf("After 8min (not processed): %d%n", allIntents - allDiag[0]);
    }
}
