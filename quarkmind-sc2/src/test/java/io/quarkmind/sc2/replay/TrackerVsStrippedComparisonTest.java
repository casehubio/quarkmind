package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class TrackerVsStrippedComparisonTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    private static final Set<String> AUTO_SPAWN_UNITS = Set.of(
        "Larva", "Interceptor", "Broodling", "BroodlingEscort",
        "LocustMP", "LocustMPFlying", "InfestedTerran",
        "AutoTurret", "PointDefenseDrone", "NydusCanal",
        "AdeptPhaseShift", "DisruptorPhased", "KD8Charge",
        "InfestedTerransEgg", "LocustMPPrecursor",
        "Changeling", "ChangelingMarine", "ChangelingZealot",
        "ChangelingZergling", "ChangelingZerglingWings",
        "ParasiticBombDummy", "ParasiticBombRelayDummy"
    );

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var s = Files.list(ORACLE_DIR)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void compareTrackerVsStrippedExtraction() throws Exception {
        List<Path> replays;
        try (var s = Files.list(ORACLE_DIR)) {
            replays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        var tracker = new TrackerEventFeatureExtractor();
        var stripped = new StrippedReplayFeatureExtractor();

        Map<String, int[]> perTypeDelta = new TreeMap<>();
        int totalTrackerUnits = 0;
        int totalStrippedUnits = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.trackerEvents == null) continue;

            Map<String, Object> trackerResult = tracker.extract(replay);
            Map<String, Object> strippedResult;
            try {
                strippedResult = stripped.extract(replayPath);
            } catch (Exception e) { continue; }

            Map<String, Integer> trackerCounts = countUnitsByType(trackerResult);
            Map<String, Integer> strippedCounts = countUnitsByType(strippedResult);

            var allTypes = new java.util.TreeSet<>(trackerCounts.keySet());
            allTypes.addAll(strippedCounts.keySet());

            for (String type : allTypes) {
                int tc = trackerCounts.getOrDefault(type, 0);
                int sc = strippedCounts.getOrDefault(type, 0);
                totalTrackerUnits += tc;
                totalStrippedUnits += sc;
                perTypeDelta.computeIfAbsent(type, k -> new int[2]);
                perTypeDelta.get(type)[0] += tc;
                perTypeDelta.get(type)[1] += sc;
            }
        }

        System.out.println("\n=== Tracker vs Stripped Extraction Comparison (118 oracle replays) ===\n");
        System.out.printf("%-25s  %8s %8s %8s  %s%n",
            "UnitType", "Tracker", "Stripped", "Delta", "Ratio%");
        System.out.println("-".repeat(75));

        for (var e : perTypeDelta.entrySet()) {
            int tc = e.getValue()[0];
            int sc = e.getValue()[1];
            int delta = sc - tc;
            double ratio = tc > 0 ? 100.0 * sc / tc : 0;
            if (tc > 0 || sc > 0) {
                System.out.printf("%-25s  %8d %8d %+8d  %5.1f%%%n",
                    e.getKey(), tc, sc, delta, ratio);
            }
        }

        System.out.printf("%n%-25s  %8d %8d %+8d  %5.1f%%%n",
            "TOTAL", totalTrackerUnits, totalStrippedUnits,
            totalStrippedUnits - totalTrackerUnits,
            totalTrackerUnits > 0 ? 100.0 * totalStrippedUnits / totalTrackerUnits : 0);

        System.out.printf("%nTracker extractor: ground truth (100%% by definition)%n");
        System.out.printf("Stripped extractor: %.1f%% of ground truth (CmdEvent-based with multiplier)%n",
            totalTrackerUnits > 0 ? 100.0 * totalStrippedUnits / totalTrackerUnits : 0);

        assertThat(totalTrackerUnits).isGreaterThan(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> countUnitsByType(Map<String, Object> json) {
        Map<String, Integer> counts = new TreeMap<>();
        List<Map<String, Object>> events = (List<Map<String, Object>>) json.get("trackerEvents");
        for (Map<String, Object> e : events) {
            String evtType = (String) e.get("evtTypeName");
            if (!"UnitBorn".equals(evtType)) continue;
            String unitType = (String) e.get("unitTypeName");
            if (AUTO_SPAWN_UNITS.contains(unitType)) continue;
            counts.merge(unitType, 1, Integer::sum);
        }
        return counts;
    }
}
