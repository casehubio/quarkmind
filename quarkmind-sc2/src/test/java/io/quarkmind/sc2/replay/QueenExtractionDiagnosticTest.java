package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class QueenExtractionDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_5MIN = (int) (5 * 60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final long LOOPS_5MIN = (long) TICKS_5MIN * SC2Data.LOOPS_PER_TICK;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void countQueenIntentsVsGroundTruth() throws Exception {
        int zergRuns = 0;
        int totalGtQueens = 0;
        int totalQueenIntents = 0;
        int queenIntentsBefore5min = 0;
        int replaysProcessed = 0;

        try (var stream = Files.list(ORACLE_DIR)) {
            for (Path replayPath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay replay;
                try {
                    replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
                } catch (Exception e) { continue; }
                if (replay == null || replay.details == null) continue;
                var players = replay.details.getPlayerList();
                if (players.length < 2) continue;

                replaysProcessed++;

                for (int playerId = 1; playerId <= 2; playerId++) {
                    hu.scelight.sc2.rep.model.details.Race scRace = players[playerId - 1].getRace();
                    if (scRace != hu.scelight.sc2.rep.model.details.Race.ZERG) continue;

                    zergRuns++;

                    ReplayCommandStream commands;
                    try {
                        commands = ReplayCommandExtractor.extract(replayPath, playerId);
                    } catch (Exception e) { continue; }

                    int queenIntentsThisRun = 0;
                    int queenIntentsBefore5minThisRun = 0;
                    for (TimedIntent ti : commands.intents()) {
                        if (ti.intent() instanceof TrainIntent t && t.unitType() == UnitType.QUEEN) {
                            queenIntentsThisRun++;
                            if (ti.loop() < LOOPS_5MIN) {
                                queenIntentsBefore5minThisRun++;
                            }
                        }
                    }
                    totalQueenIntents += queenIntentsThisRun;
                    queenIntentsBefore5min += queenIntentsBefore5minThisRun;

                    int gtQueens = 0;
                    try {
                        ReplaySimulatedGame gt = new ReplaySimulatedGame(replayPath, playerId);
                        for (int tick = 0; tick < TICKS_5MIN && !gt.isComplete(); tick++) {
                            gt.tick();
                        }
                        gtQueens = (int) gt.snapshot().myUnits().stream()
                                .filter(u -> u.type() == UnitType.QUEEN).count();
                    } catch (Exception e) { /* skip */ }
                    totalGtQueens += gtQueens;
                }
            }
        }

        System.out.printf("=== Queen Extraction Diagnostic ===%n");
        System.out.printf("Replays processed:            %d%n", replaysProcessed);
        System.out.printf("Zerg player runs:             %d%n", zergRuns);
        System.out.printf("GT Queens at 5min:            %d  (%.1f per Zerg run)%n",
                totalGtQueens, zergRuns > 0 ? (double) totalGtQueens / zergRuns : 0);
        System.out.printf("Queen TrainIntents (total):   %d  (%.1f per Zerg run)%n",
                totalQueenIntents, zergRuns > 0 ? (double) totalQueenIntents / zergRuns : 0);
        System.out.printf("Queen TrainIntents (<5min):   %d  (%.1f per Zerg run)%n",
                queenIntentsBefore5min, zergRuns > 0 ? (double) queenIntentsBefore5min / zergRuns : 0);
        System.out.printf("Intent capture rate (<5min):  %.1f%%%n",
                totalGtQueens > 0 ? 100.0 * queenIntentsBefore5min / totalGtQueens : 0);

        assertThat(zergRuns).as("Must process at least 50 Zerg player runs").isGreaterThanOrEqualTo(50);
    }
}
