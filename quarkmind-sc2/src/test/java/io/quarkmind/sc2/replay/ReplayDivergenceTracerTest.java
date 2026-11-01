package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("diagnostic")
class ReplayDivergenceTracerTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    private static final UnitType TRACE_UNIT_TYPE = UnitType.MARINE;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    record TrainEvent(int tick, TrainIntent intent, String buildingTag,
                      boolean success, String reason) {}

    @Test
    @EnabledIf("oracleExists")
    void traceWorstDivergence() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        Path worstReplay = null;
        int worstPlayerId = -1;
        int worstDelta = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);
                    if (race != Race.TERRAN) continue;

                    ReplaySimulatedGame gt = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    DivergenceReport report = ReplayValidationHarness.run(gt, intents, TICK_LIMIT, race);

                    int tickIndex = 5 * TICKS_PER_MINUTE - 1;
                    if (tickIndex >= report.ticks().size()) continue;
                    var snap = report.ticks().get(tickIndex);

                    int gtCount = snap.groundTruthUnitsByType().getOrDefault(TRACE_UNIT_TYPE, 0);
                    int emCount = snap.emulatedUnitsByType().getOrDefault(TRACE_UNIT_TYPE, 0);
                    int deficit = gtCount - emCount;
                    if (deficit > worstDelta) {
                        worstDelta = deficit;
                        worstReplay = replayPath;
                        worstPlayerId = playerId;
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        assertNotNull(worstReplay, "Should find at least one Terran replay with divergence");
        System.out.printf("%n=== Tracing %s (player %d) for %s (delta=%d at 5min) ===%n%n",
            worstReplay.getFileName(), worstPlayerId, TRACE_UNIT_TYPE, worstDelta);

        ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(worstReplay, worstPlayerId);
        List<TimedIntent> intents = TrackerIntentExtractor.extract(worstReplay, worstPlayerId);
        Replay replay = RepParserEngine.parseReplay(worstReplay, EnumSet.of(RepContent.DETAILS));
        Race race = TrackerIntentExtractor.resolvePlayerRace(replay, worstPlayerId);

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));

        List<TrainEvent> trainEvents = new ArrayList<>();
        emulated.setIntentExecutionListener((tick, intent, bldgTag, success, reason) -> {
            if (intent.unitType() == TRACE_UNIT_TYPE) {
                trainEvents.add(new TrainEvent(tick, intent, bldgTag, success, reason));
            }
        });

        DivergenceReport report = ReplayValidationHarness.run(
            groundTruth, intents, TICK_LIMIT, emulated, race);

        System.out.println("--- Intent Execution Timeline ---");
        System.out.printf("%-6s | %-8s | %-20s | %-7s | %s%n",
            "Tick", "Type", "Building", "Success", "Reason");
        System.out.println("-".repeat(70));
        for (var e : trainEvents) {
            System.out.printf("%-6d | %-8s | %-20s | %-7s | %s%n",
                e.tick(), e.intent().unitType(),
                e.buildingTag() != null ? e.buildingTag() : "N/A",
                e.success() ? "OK" : "FAIL",
                e.reason() != null ? e.reason() : "");
        }

        System.out.println("\n--- Per-Minute Count Comparison ---");
        for (int cp : new int[]{1, 3, 5, 7}) {
            int tickIndex = cp * TICKS_PER_MINUTE - 1;
            if (tickIndex >= report.ticks().size()) continue;
            var snap = report.ticks().get(tickIndex);
            int gtCount = snap.groundTruthUnitsByType().getOrDefault(TRACE_UNIT_TYPE, 0);
            int emCount = snap.emulatedUnitsByType().getOrDefault(TRACE_UNIT_TYPE, 0);
            System.out.printf("%dmin: GT=%d Em=%d delta=%+d%n", cp, gtCount, emCount, emCount - gtCount);
        }

        System.out.println("\n--- Train Diagnostics ---");
        var diag = emulated.trackerTrainDiagnostics();
        var unitDiag = diag.get(TRACE_UNIT_TYPE);
        if (unitDiag != null) {
            System.out.printf("received=%d trained=%d bldg404=%d prodBlocked=%d insuffRes=%d queueFull=%d deferOverflow=%d%n",
                unitDiag[0], unitDiag[1], unitDiag[2], unitDiag[3], unitDiag[4], unitDiag[5], unitDiag[6]);
        }

        System.out.println("\n--- First Divergence ---");
        for (int tick = 0; tick < report.ticks().size(); tick++) {
            var snap = report.ticks().get(tick);
            int gtCount = snap.groundTruthUnitsByType().getOrDefault(TRACE_UNIT_TYPE, 0);
            int emCount = snap.emulatedUnitsByType().getOrDefault(TRACE_UNIT_TYPE, 0);
            if (gtCount != emCount) {
                System.out.printf("First divergence at tick %d (~%.1f min): GT=%d Em=%d%n",
                    tick, tick / (double) TICKS_PER_MINUTE, gtCount, emCount);
                for (var e : trainEvents) {
                    if (Math.abs(e.tick() - tick) <= 5) {
                        System.out.printf("  tick %d: %s -> %s (bldg=%s, reason=%s)%n",
                            e.tick(), e.intent().unitType(),
                            e.success() ? "OK" : "FAIL",
                            e.buildingTag(), e.reason());
                    }
                }
                break;
            }
        }
    }
}
