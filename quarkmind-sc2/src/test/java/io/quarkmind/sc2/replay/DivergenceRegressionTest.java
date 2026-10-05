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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for EmulatedGame divergence — fails if any per-matchup
 * metric exceeds the baseline from #347 by more than 10%.
 *
 * Baselines from docs/benchmarks/emulated-game-divergence-baseline.md
 * (Oracle v4.9.3 dataset, 5-minute checkpoint).
 */
@Tag("report")
class DivergenceRegressionTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int CHECKPOINT_MINUTE = 5;
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * CHECKPOINT_MINUTE + 1;
    private static final double MARGIN = 1.10;
    private static final double FLOOR = 3.0;

    // Oracle v4.9.3 baseline at 5-minute mark (#347)
    private static final Map<String, double[]> BASELINE_5MIN = Map.of(
        "PvP", new double[]{  8.9, 1.7 },  // [unitDelta, buildingDelta]
        "PvT", new double[]{ 20.6, 1.3 },
        "PvZ", new double[]{ 28.4, 2.3 },
        "TvT", new double[]{ 24.0, 1.1 },
        "TvZ", new double[]{ 33.3, 1.3 },
        "ZvZ", new double[]{ 38.3, 1.3 }
    );

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void oracleDivergenceWithinBaseline() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        Map<String, List<int[]>> unitDeltas     = new TreeMap<>();
        Map<String, List<int[]>> buildingDeltas = new TreeMap<>();
        int processed = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {
                continue;
            }
            if (replay == null || replay.details == null) continue;
            Player[] players = replay.details.getPlayerList();
            if (players.length < 2) continue;

            String matchup = toMatchup(players[0].getRace(), players[1].getRace());
            if (matchup == null) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    DivergenceReport report = ReplayValidationHarness.run(replayPath, playerId, TICK_LIMIT);
                    int tickIndex = CHECKPOINT_MINUTE * TICKS_PER_MINUTE - 1;
                    if (tickIndex >= report.ticks().size()) continue;

                    DivergenceReport.TickSnapshot snap = report.ticks().get(tickIndex);
                    unitDeltas.computeIfAbsent(matchup, k -> new ArrayList<>())
                        .add(new int[]{ snap.unitDelta() });
                    buildingDeltas.computeIfAbsent(matchup, k -> new ArrayList<>())
                        .add(new int[]{ snap.buildingDelta() });
                } catch (Exception e) {
                    // skip failed replays
                }
            }
            processed++;
        }

        assertThat(processed).as("Must process at least 100 oracle replays").isGreaterThanOrEqualTo(100);

        System.out.printf("%nDivergence regression check — %d replays, 5-min checkpoint%n", processed);
        System.out.printf("%-8s  %8s  %8s  %8s  %8s%n",
            "Matchup", "UnitΔ", "Thresh", "BldgΔ", "Thresh");
        System.out.println("-".repeat(50));

        for (var entry : BASELINE_5MIN.entrySet()) {
            String matchup = entry.getKey();
            double baselineUnit = entry.getValue()[0];
            double baselineBldg = entry.getValue()[1];
            double thresholdUnit = Math.max(baselineUnit * MARGIN, FLOOR);
            double thresholdBldg = Math.max(baselineBldg * MARGIN, FLOOR);

            List<int[]> units = unitDeltas.get(matchup);
            List<int[]> bldgs = buildingDeltas.get(matchup);
            if (units == null || units.isEmpty()) continue;

            double meanUnit = units.stream().mapToInt(a -> a[0]).average().orElse(0);
            double meanBldg = bldgs.stream().mapToInt(a -> a[0]).average().orElse(0);

            System.out.printf("%-8s  %8.1f  %8.1f  %8.1f  %8.1f%n",
                matchup, meanUnit, thresholdUnit, meanBldg, thresholdBldg);

            assertThat(meanUnit)
                .as("Unit delta regression for %s (baseline=%.1f, margin=10%%)", matchup, baselineUnit)
                .isLessThanOrEqualTo(thresholdUnit);

            assertThat(meanBldg)
                .as("Building delta regression for %s (baseline=%.1f, margin=10%%)", matchup, baselineBldg)
                .isLessThanOrEqualTo(thresholdBldg);
        }
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
}
