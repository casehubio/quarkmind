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
import io.quarkmind.sc2.intent.TrainIntent;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Tag("diagnostic")
class RejectionReasonDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final int[] CHECKPOINT = {5};
    private static final Set<UnitType> EXCLUDED = EnumSet.of(UnitType.EGG, UnitType.MULE);
    private static final Set<UnitType> DETAILED_TYPES = EnumSet.of(
        UnitType.SIEGE_TANK, UnitType.REAPER, UnitType.HELLION, UnitType.WIDOW_MINE,
        UnitType.CYCLONE, UnitType.MEDIVAC, UnitType.VIKING, UnitType.STALKER,
        UnitType.ADEPT, UnitType.PHOENIX, UnitType.MARINE);

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    record Rejection(String replayName, int playerId, UnitType unitType,
                     String reason, String buildingTag, int tick) {}

    @Test
    @EnabledIf("oracleExists")
    void diagnoseRejectionReasons() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        Map<UnitType, long[]> unitAccum5 = new EnumMap<>(UnitType.class);
        Map<UnitType, Map<String, Integer>> rejectionsByType = new EnumMap<>(UnitType.class);
        List<Rejection> detailedRejections = new ArrayList<>();
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

                    String replayName = replayPath.getFileName().toString();
                    int pid = playerId;
                    emulated.setIntentExecutionListener((tick, intent, bldgTag, success, reason) -> {
                        if (!success && reason != null) {
                            rejectionsByType
                                .computeIfAbsent(intent.unitType(), k -> new TreeMap<>())
                                .merge(reason, 1, Integer::sum);
                            if (DETAILED_TYPES.contains(intent.unitType())) {
                                detailedRejections.add(new Rejection(
                                    replayName, pid, intent.unitType(),
                                    reason, bldgTag, tick));
                            }
                        }
                    });

                    DivergenceReport report = ReplayValidationHarness.run(
                        groundTruth, intents, TICK_LIMIT, emulated, race);

                    int tickIndex = 5 * TICKS_PER_MINUTE - 1;
                    if (tickIndex >= report.ticks().size()) continue;
                    var snap = report.ticks().get(tickIndex);

                    for (var entry : snap.groundTruthUnitsByType().entrySet()) {
                        if (EXCLUDED.contains(entry.getKey())) continue;
                        long[] acc = unitAccum5.computeIfAbsent(
                            entry.getKey(), k -> new long[]{0, 0});
                        acc[0] += entry.getValue();
                        int emVal = snap.emulatedUnitsByType()
                            .getOrDefault(entry.getKey(), 0);
                        acc[1] += Math.min(emVal, entry.getValue());
                    }

                    totalPlayerRuns++;
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.printf("%n=== Rejection Reason Diagnostic — %d player runs ===%n%n", totalPlayerRuns);

        System.out.println("--- Per-UnitType Rejection Summary (units with deficit at 5min) ---");
        System.out.printf("%-15s | %4s | %4s | %5s | %10s | %8s | %12s | %9s | %10s | %14s%n",
            "UnitType", "GT", "Em", "Delta", "wrong_bldg", "bldg_404", "prod_blocked",
            "insuf_res", "queue_full", "defer_overflow");
        System.out.println("-".repeat(120));

        for (var entry : new TreeMap<>(unitAccum5).entrySet()) {
            long gt = entry.getValue()[0];
            long em = entry.getValue()[1];
            long delta = gt - em;
            if (delta <= 0) continue;

            Map<String, Integer> rejections = rejectionsByType.getOrDefault(entry.getKey(), Map.of());
            System.out.printf("%-15s | %4d | %4d | %5d | %10d | %8d | %12d | %9d | %10d | %14d%n",
                entry.getKey(), gt, em, delta,
                rejections.getOrDefault("wrong_building_type", 0),
                rejections.getOrDefault("building_not_found", 0),
                rejections.getOrDefault("production_blocked", 0),
                rejections.getOrDefault("insufficient_resources", 0),
                rejections.getOrDefault("queue_full", 0),
                rejections.getOrDefault("defer_overflow", 0));
        }

        System.out.println("\n--- Detailed Rejections for Key Unit Types (first 5 per type) ---");
        for (UnitType ut : DETAILED_TYPES) {
            List<Rejection> filtered = detailedRejections.stream()
                .filter(r -> r.unitType() == ut)
                .limit(5)
                .toList();
            if (filtered.isEmpty()) continue;
            System.out.printf("%n%s:%n", ut);
            for (Rejection r : filtered) {
                System.out.printf("  %s p%d tick=%d reason=%s bldgTag=%s%n",
                    r.replayName(), r.playerId(), r.tick(), r.reason(), r.buildingTag());
            }
        }

        System.out.println("\n--- All Rejection Reasons by Unit Type ---");
        for (var entry : new TreeMap<>(rejectionsByType).entrySet()) {
            System.out.printf("%-15s: %s%n", entry.getKey(), entry.getValue());
        }
    }
}
