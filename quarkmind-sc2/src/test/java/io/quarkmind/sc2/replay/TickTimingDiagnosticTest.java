package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.factory.RepContent;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TimedIntent;
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
import java.util.TreeMap;

@Tag("diagnostic")
class TickTimingDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final Set<UnitType> EXCLUDED_TYPES = EnumSet.of(UnitType.EGG, UnitType.MULE);
    private static final Set<UnitType> FOCUS_TYPES = EnumSet.of(
        UnitType.MARINE, UnitType.HELLION, UnitType.WIDOW_MINE, UnitType.MEDIVAC,
        UnitType.PROBE, UnitType.PHOENIX, UnitType.STALKER, UnitType.REAPER);

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void tickTimingDiagnostic() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        int checkpoint5 = 5 * TICKS_PER_MINUTE - 1;
        int checkpoint7 = 7 * TICKS_PER_MINUTE - 1;
        int[] offsets = {1, 2, 5, 10, 20};

        // Table 1: aggregate deficit at 5-min
        Map<UnitType, long[]> deficitAccum = new EnumMap<>(UnitType.class); // [gt, em, replayCount]

        // Table 2: how quickly deficits close — per type, per offset, count of recovered units
        Map<UnitType, int[]> recoveryAccum = new EnumMap<>(UnitType.class); // [+1, +2, +5, +10, +20, never]

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
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    DivergenceReport report = ReplayValidationHarness.run(
                        groundTruth, intents, TICK_LIMIT, race);

                    List<DivergenceReport.TickSnapshot> ticks = report.ticks();
                    if (checkpoint5 >= ticks.size()) continue;

                    var snap5 = ticks.get(checkpoint5);
                    var snap7 = checkpoint7 < ticks.size() ? ticks.get(checkpoint7) : null;

                    for (UnitType ut : UnitType.values()) {
                        if (EXCLUDED_TYPES.contains(ut)) continue;
                        int gt5 = snap5.groundTruthUnitsByType().getOrDefault(ut, 0);
                        int em5 = snap5.emulatedUnitsByType().getOrDefault(ut, 0);
                        int deficit = gt5 - em5;

                        if (gt5 > 0) {
                            long[] acc = deficitAccum.computeIfAbsent(ut, k -> new long[3]);
                            acc[0] += gt5;
                            acc[1] += Math.min(em5, gt5);
                            if (deficit > 0) acc[2]++;
                        }

                        if (deficit > 0) {
                            int[] rec = recoveryAccum.computeIfAbsent(ut, k -> new int[offsets.length + 1]);

                            int prevDeficit = deficit;
                            for (int oi = 0; oi < offsets.length; oi++) {
                                int ti = checkpoint5 + offsets[oi];
                                if (ti >= ticks.size()) break;
                                var snapOff = ticks.get(ti);
                                int gtOff = snapOff.groundTruthUnitsByType().getOrDefault(ut, 0);
                                int emOff = snapOff.emulatedUnitsByType().getOrDefault(ut, 0);
                                int deficitOff = Math.max(0, gtOff - emOff);
                                int recovered = prevDeficit - deficitOff;
                                if (recovered > 0) rec[oi] += recovered;
                                prevDeficit = deficitOff;
                            }

                            // "Never" = still missing at 7-min
                            if (snap7 != null) {
                                int gt7 = snap7.groundTruthUnitsByType().getOrDefault(ut, 0);
                                int em7 = snap7.emulatedUnitsByType().getOrDefault(ut, 0);
                                int deficit7 = Math.max(0, gt7 - em7);
                                rec[offsets.length] += deficit7;
                            }
                        }
                    }
                    totalPlayerRuns++;
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.printf("%n=== Tick Timing Diagnostic — %d replays, %d player runs ===%n%n", replays.size(), totalPlayerRuns);

        // Table 1
        System.out.println("### Table 1: Aggregate deficit at 5-min checkpoint");
        System.out.println("| Type | GT | Em | Deficit | Replays with deficit |");
        System.out.println("|---|---|---|---|---|");
        var sorted1 = new TreeMap<>(deficitAccum);
        for (var entry : sorted1.entrySet()) {
            long gt = entry.getValue()[0];
            long em = entry.getValue()[1];
            long deficit = gt - em;
            long replayCount = entry.getValue()[2];
            if (deficit > 0) {
                System.out.printf("| %s | %d | %d | %d | %d |%n",
                    entry.getKey(), gt, em, deficit, replayCount);
            }
        }

        // Table 2
        System.out.println();
        System.out.println("### Table 2: How quickly deficits close (aggregate)");
        System.out.printf("| Type | +1 tick | +2 ticks | +5 ticks | +10 ticks | +20 ticks | Never (7-min) |%n");
        System.out.println("|---|---|---|---|---|---|---|");
        var sorted2 = new TreeMap<>(recoveryAccum);
        for (var entry : sorted2.entrySet()) {
            int[] rec = entry.getValue();
            System.out.printf("| %s | %d | %d | %d | %d | %d | %d |%n",
                entry.getKey(), rec[0], rec[1], rec[2], rec[3], rec[4], rec[5]);
        }
    }
}
