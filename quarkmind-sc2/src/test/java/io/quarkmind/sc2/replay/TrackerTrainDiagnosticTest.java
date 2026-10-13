package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Tag("diagnostic")
class TrackerTrainDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 6;
    private static final Set<UnitType> EXCLUDED_TYPES = EnumSet.of(UnitType.EGG, UnitType.MULE);
    private static final String[] REASON_NAMES = {
        "received", "trained", "building_not_found", "production_blocked",
        "insufficient_resources", "queue_full", "defer_overflow"
    };

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void trainRejectionDiagnostics() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        Map<UnitType, int[]> aggregate = new EnumMap<>(UnitType.class);
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

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));

                    ReplayValidationHarness.run(groundTruth, intents, TICK_LIMIT, emulated, race);

                    for (var entry : emulated.trackerTrainDiagnostics().entrySet()) {
                        if (EXCLUDED_TYPES.contains(entry.getKey())) continue;
                        int[] acc = aggregate.computeIfAbsent(entry.getKey(), k -> new int[7]);
                        int[] src = entry.getValue();
                        for (int i = 0; i < 7; i++) acc[i] += src[i];
                    }
                    totalPlayerRuns++;
                } catch (Exception e) { /* skip failed replays */ }
            }
        }

        System.out.println("# Tracker Train Rejection Diagnostics");
        System.out.printf("Replays: %d, Player runs: %d, Tick limit: %d ticks (%d min)%n%n",
            replays.size(), totalPlayerRuns, TICK_LIMIT, TICK_LIMIT / TICKS_PER_MINUTE);

        System.out.println("| Unit Type | Received | Trained | Bldg Not Found | Prod Blocked | Insuff Resources | Queue Full | Defer Overflow | Train% |");
        System.out.println("|---|---|---|---|---|---|---|---|---|");

        Map<UnitType, int[]> sorted = new TreeMap<>(aggregate);
        int totalReceived = 0, totalTrained = 0;
        for (var entry : sorted.entrySet()) {
            int[] d = entry.getValue();
            totalReceived += d[0];
            totalTrained += d[1];
            double pct = d[0] > 0 ? d[1] * 100.0 / d[0] : 100.0;
            System.out.printf("| %s | %d | %d | %d | %d | %d | %d | %d | %.1f%% |%n",
                entry.getKey(), d[0], d[1], d[2], d[3], d[4], d[5], d[6], pct);
        }
        double totalPct = totalReceived > 0 ? totalTrained * 100.0 / totalReceived : 100.0;
        System.out.printf("| **TOTAL** | %d | %d | | | | | | **%.1f%%** |%n",
            totalReceived, totalTrained, totalPct);

        System.out.println("\n## Top Missing Units (received - trained)");
        System.out.println("| Unit Type | Missed | Primary Reason | Count |");
        System.out.println("|---|---|---|---|");

        sorted.entrySet().stream()
            .filter(e -> e.getValue()[0] - e.getValue()[1] > 0)
            .sorted((a, b) -> (b.getValue()[0] - b.getValue()[1]) - (a.getValue()[0] - a.getValue()[1]))
            .limit(15)
            .forEach(e -> {
                int[] d = e.getValue();
                int missed = d[0] - d[1];
                int maxIdx = 2;
                for (int i = 3; i < 7; i++) {
                    if (d[i] > d[maxIdx]) maxIdx = i;
                }
                System.out.printf("| %s | %d | %s | %d |%n",
                    e.getKey(), missed, REASON_NAMES[maxIdx], d[maxIdx]);
            });
    }
}
