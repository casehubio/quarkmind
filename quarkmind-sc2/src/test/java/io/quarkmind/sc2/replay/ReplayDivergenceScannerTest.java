package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("report")
class ReplayDivergenceScannerTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final Set<UnitType> TARGET_TYPES = EnumSet.of(
        UnitType.MARINE, UnitType.HELLION, UnitType.WIDOW_MINE,
        UnitType.SIEGE_TANK, UnitType.MEDIVAC, UnitType.REAPER,
        UnitType.SCV, UnitType.MARAUDER, UnitType.VIKING,
        UnitType.BANSHEE, UnitType.CYCLONE, UnitType.LIBERATOR);

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    record DivergenceEntry(String replayHash, int playerId, UnitType unitType,
                           int checkpoint, int gt, int em, int delta) {}

    @Test
    @EnabledIf("oracleExists")
    void scanTerranDivergences() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        List<DivergenceEntry> entries = new ArrayList<>();
        int processed = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            String hash = replayPath.getFileName().toString().replace(".SC2Replay", "");

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);
                    if (race != Race.TERRAN) continue;

                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    DivergenceReport report = ReplayValidationHarness.run(
                        groundTruth, intents, TICK_LIMIT, race);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= report.ticks().size()) continue;
                        var snap = report.ticks().get(tickIndex);

                        for (UnitType ut : TARGET_TYPES) {
                            int gtCount = snap.groundTruthUnitsByType().getOrDefault(ut, 0);
                            int emCount = snap.emulatedUnitsByType().getOrDefault(ut, 0);
                            int delta = emCount - gtCount;
                            if (delta != 0) {
                                entries.add(new DivergenceEntry(
                                    hash, playerId, ut, cp, gtCount, emCount, delta));
                            }
                        }
                    }
                    processed++;
                } catch (Exception e) {
                    System.err.printf("[Scanner] Error on %s player %d: %s%n",
                        replayPath.getFileName(), playerId, e.getMessage());
                }
            }
        }

        entries.sort(Comparator.comparingInt((DivergenceEntry e) -> Math.abs(e.delta()))
            .reversed().thenComparingInt(DivergenceEntry::checkpoint));

        System.out.println("\n=== Terran Unit Divergence Report ===");
        System.out.printf("Processed: %d Terran player runs across %d replays%n%n",
            processed, replays.size());
        System.out.printf("%-20s | Player | %-12s | CP | %5s | %5s | %5s%n",
            "Replay", "UnitType", "GT", "Em", "Delta");
        System.out.println("-".repeat(78));
        for (var e : entries) {
            System.out.printf("%-20s | %6d | %-12s | %2d | %5d | %5d | %+5d%n",
                e.replayHash().substring(0, Math.min(20, e.replayHash().length())),
                e.playerId(), e.unitType(), e.checkpoint(),
                e.gt(), e.em(), e.delta());
        }

        System.out.println("\n=== 5-min Summary by Unit Type ===");
        Map<UnitType, int[]> summary = new EnumMap<>(UnitType.class);
        for (var e : entries) {
            if (e.checkpoint() != 5) continue;
            int[] acc = summary.computeIfAbsent(e.unitType(), k -> new int[]{0, 0, 0});
            acc[0] += e.gt();
            acc[1] += e.em();
            acc[2] += Math.abs(e.delta());
        }
        for (var s : summary.entrySet()) {
            System.out.printf("%-12s: GT=%d Em=%d TotalAbsDelta=%d%n",
                s.getKey(), s.getValue()[0], s.getValue()[1], s.getValue()[2]);
        }

        assertTrue(processed > 0, "Should have processed at least one Terran player run");
    }
}
