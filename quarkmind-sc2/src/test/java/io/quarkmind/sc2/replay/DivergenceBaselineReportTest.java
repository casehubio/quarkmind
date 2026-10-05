package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

@Tag("report")
class DivergenceBaselineReportTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path HSC_XXVII_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2025_HomeStory_Cup_XXVII");

    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int MAX_MINUTES = 8;
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * MAX_MINUTES + 1;

    private static final List<String> ALL_MATCHUPS =
        List.of("PvP", "PvT", "PvZ", "TvT", "TvZ", "ZvZ");

    static boolean oracleExists() {
        return directoryHasReplays(ORACLE_DIR);
    }

    static boolean hscExists() {
        return directoryHasReplaysDeep(HSC_XXVII_DIR);
    }

    @Test
    @EnabledIf("oracleExists")
    void oracleDivergenceBaseline() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }
        System.out.printf("%n=== Oracle (v4.9.3) Divergence Baseline — %d replays ===%n", replays.size());
        System.out.println("Note: countWorkersPerBase hardcodes Race.PROTOSS — mineral divergence");
        System.out.printf("is less accurate for non-Protoss players.%n%n");
        runBaseline(replays);
    }

    @Test
    @EnabledIf("hscExists")
    void hscDivergenceBaseline() throws Exception {
        List<Path> replays;
        try (var stream = Files.walk(HSC_XXVII_DIR, 3)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }
        System.out.printf("%n=== HSC XXVII (baseBuild=94137) Divergence Baseline — %d replays ===%n", replays.size());
        System.out.println("Note: countWorkersPerBase hardcodes Race.PROTOSS — mineral divergence");
        System.out.printf("is less accurate for non-Protoss players.%n%n");
        runBaseline(replays);
    }

    private void runBaseline(List<Path> replays) {
        Map<String, List<int[]>> mineralDeltas  = new TreeMap<>();
        Map<String, List<int[]>> vespeneDeltas  = new TreeMap<>();
        Map<String, List<int[]>> unitDeltas     = new TreeMap<>();
        Map<String, List<int[]>> buildingDeltas = new TreeMap<>();
        int skipped   = 0;
        int processed = 0;
        int failed    = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {
                skipped++;
                continue;
            }
            if (replay == null || replay.details == null) {
                skipped++;
                continue;
            }
            Player[] players = replay.details.getPlayerList();
            if (players.length < 2) {
                skipped++;
                continue;
            }

            Race r1 = players[0].getRace();
            Race r2 = players[1].getRace();
            String matchup = toMatchup(r1, r2);
            if (matchup == null) {
                skipped++;
                continue;
            }

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    DivergenceReport report = ReplayValidationHarness.run(replayPath, playerId, TICK_LIMIT);
                    int[] mins  = sampleAtMinutes(report, DivergenceReport.TickSnapshot::mineralDelta);
                    int[] vesps = sampleAtMinutes(report, DivergenceReport.TickSnapshot::vespeneDelta);
                    int[] units = sampleAtMinutes(report, DivergenceReport.TickSnapshot::unitDelta);
                    int[] bldgs = sampleAtMinutes(report, DivergenceReport.TickSnapshot::buildingDelta);

                    mineralDeltas.computeIfAbsent(matchup, k -> new ArrayList<>()).add(mins);
                    vespeneDeltas.computeIfAbsent(matchup, k -> new ArrayList<>()).add(vesps);
                    unitDeltas.computeIfAbsent(matchup, k -> new ArrayList<>()).add(units);
                    buildingDeltas.computeIfAbsent(matchup, k -> new ArrayList<>()).add(bldgs);
                } catch (Exception e) {
                    failed++;
                }
            }
            processed++;
        }

        System.out.printf("Processed: %d  Skipped: %d  Failed player runs: %d%n%n", processed, skipped, failed);
        printMatchupTable("Mineral delta (mean)", mineralDeltas);
        printMatchupTable("Vespene delta (mean)", vespeneDeltas);
        printMatchupTable("Unit count delta (mean)", unitDeltas);
        printMatchupTable("Building count delta (mean)", buildingDeltas);
    }

    private static int[] sampleAtMinutes(DivergenceReport report,
                                         ToIntFunction<DivergenceReport.TickSnapshot> metric) {
        int[] result = new int[MAX_MINUTES];
        List<DivergenceReport.TickSnapshot> ticks = report.ticks();
        for (int min = 0; min < MAX_MINUTES; min++) {
            int tickIndex = (min + 1) * TICKS_PER_MINUTE - 1;
            if (tickIndex < ticks.size()) {
                result[min] = metric.applyAsInt(ticks.get(tickIndex));
            } else {
                result[min] = -1;
            }
        }
        return result;
    }

    private void printMatchupTable(String label, Map<String, List<int[]>> data) {
        System.out.printf("--- %s ---%n", label);
        System.out.printf("%-8s  %6s", "Matchup", "N");
        for (int m = 1; m <= MAX_MINUTES; m++) {
            System.out.printf("  %6s", m + "min");
        }
        System.out.println();
        System.out.println("-".repeat(8 + 8 + MAX_MINUTES * 8));

        for (String matchup : ALL_MATCHUPS) {
            List<int[]> entries = data.get(matchup);
            if (entries == null || entries.isEmpty()) continue;

            System.out.printf("%-8s  %6d", matchup, entries.size());
            for (int min = 0; min < MAX_MINUTES; min++) {
                final int m = min;
                double mean = entries.stream()
                    .filter(e -> e[m] >= 0)
                    .mapToInt(e -> e[m])
                    .average()
                    .orElse(-1);
                if (mean < 0) {
                    System.out.printf("  %6s", "-");
                } else {
                    System.out.printf("  %6.1f", mean);
                }
            }
            System.out.println();
        }
        System.out.println();
    }

    private static String toMatchup(Race r1, Race r2) {
        String s1 = raceInitial(r1);
        String s2 = raceInitial(r2);
        if (s1 == null || s2 == null) return null;
        if (s1.compareTo(s2) <= 0) return s1 + "v" + s2;
        return s2 + "v" + s1;
    }

    private static String raceInitial(Race race) {
        if (race == Race.PROTOSS) return "P";
        if (race == Race.TERRAN)  return "T";
        if (race == Race.ZERG)    return "Z";
        return null;
    }

    private static boolean directoryHasReplays(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean directoryHasReplaysDeep(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir, 5)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }
}
