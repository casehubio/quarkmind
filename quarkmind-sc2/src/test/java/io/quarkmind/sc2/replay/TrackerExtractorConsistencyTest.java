package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class TrackerExtractorConsistencyTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var s = Files.list(ORACLE_DIR)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void extractorOutputMatchesRawTrackerEvents() throws Exception {
        List<Path> replays;
        try (var s = Files.list(ORACLE_DIR)) {
            replays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        var extractor = new TrackerEventFeatureExtractor();
        int processed = 0;
        int perfectMatch = 0;
        int totalMismatches = 0;
        Map<String, String> replayMismatches = new TreeMap<>();

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.trackerEvents == null) continue;

            processed++;
            Map<String, Object> extracted = extractor.extract(replay);

            var oracleUnitBorn = new TreeMap<String, Integer>();
            var oracleUnitInit = new TreeMap<String, Integer>();
            var oracleUpgrade = new TreeMap<String, Integer>();
            int oraclePlayerStats = 0;

            for (Event raw : replay.trackerEvents.getEvents()) {
                switch (raw.getId()) {
                    case ITrackerEvents.ID_UNIT_BORN -> {
                        IBaseUnitEvent ue = (IBaseUnitEvent) raw;
                        if (ue.getControlPlayerId() == null || ue.getControlPlayerId() == 0) continue;
                        String key = "P" + ue.getControlPlayerId() + ":" + String.valueOf(ue.getUnitTypeName()).trim();
                        oracleUnitBorn.merge(key, 1, Integer::sum);
                    }
                    case ITrackerEvents.ID_UNIT_INIT -> {
                        IBaseUnitEvent ue = (IBaseUnitEvent) raw;
                        if (ue.getControlPlayerId() == null || ue.getControlPlayerId() == 0) continue;
                        String key = "P" + ue.getControlPlayerId() + ":" + String.valueOf(ue.getUnitTypeName()).trim();
                        oracleUnitInit.merge(key, 1, Integer::sum);
                    }
                    case ITrackerEvents.ID_UPGRADE -> {
                        IUpgradeEvent up = (IUpgradeEvent) raw;
                        if (up.getPlayerId() == null) continue;
                        String key = "P" + up.getPlayerId() + ":" + String.valueOf(up.getUpgradeTypeName()).trim();
                        oracleUpgrade.merge(key, 1, Integer::sum);
                    }
                    case ITrackerEvents.ID_PLAYER_STATS -> {
                        IPlayerStatsEvent ps = (IPlayerStatsEvent) raw;
                        if (ps.getPlayerId() == null || ps.getPlayerId() == 0) continue;
                        oraclePlayerStats++;
                    }
                    default -> {}
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) extracted.get("trackerEvents");

            var extractedUnitBorn = new TreeMap<String, Integer>();
            var extractedUnitInit = new TreeMap<String, Integer>();
            var extractedUpgrade = new TreeMap<String, Integer>();
            int extractedPlayerStats = 0;

            for (Map<String, Object> e : events) {
                String evtType = (String) e.get("evtTypeName");
                switch (evtType) {
                    case "UnitBorn" -> {
                        int pid = ((Number) e.get("controlPlayerId")).intValue();
                        String key = "P" + pid + ":" + e.get("unitTypeName");
                        extractedUnitBorn.merge(key, 1, Integer::sum);
                    }
                    case "UnitInit" -> {
                        int pid = ((Number) e.get("controlPlayerId")).intValue();
                        String key = "P" + pid + ":" + e.get("unitTypeName");
                        extractedUnitInit.merge(key, 1, Integer::sum);
                    }
                    case "Upgrade" -> {
                        int pid = ((Number) e.get("playerId")).intValue();
                        String key = "P" + pid + ":" + e.get("upgradeTypeName");
                        extractedUpgrade.merge(key, 1, Integer::sum);
                    }
                    case "PlayerStats" -> extractedPlayerStats++;
                    default -> {}
                }
            }

            var mismatches = new StringBuilder();
            compareCounts("UnitBorn", oracleUnitBorn, extractedUnitBorn, mismatches);
            compareCounts("UnitInit", oracleUnitInit, extractedUnitInit, mismatches);
            compareCounts("Upgrade", oracleUpgrade, extractedUpgrade, mismatches);

            if (oraclePlayerStats != extractedPlayerStats) {
                mismatches.append(String.format("  PlayerStats: oracle=%d extracted=%d%n",
                    oraclePlayerStats, extractedPlayerStats));
            }

            if (mismatches.isEmpty()) {
                perfectMatch++;
            } else {
                totalMismatches++;
                replayMismatches.put(replayPath.getFileName().toString(), mismatches.toString());
            }
        }

        System.out.println("\n=== Tracker Extractor Consistency Check ===\n");
        System.out.printf("Replays: %d processed, %d perfect match, %d with mismatches%n",
            processed, perfectMatch, totalMismatches);

        if (!replayMismatches.isEmpty()) {
            System.out.println("\nMismatches:");
            for (var entry : replayMismatches.entrySet()) {
                System.out.printf("\n%s:%n%s", entry.getKey(), entry.getValue());
            }
        }

        System.out.printf("%nResult: %s%n",
            totalMismatches == 0 ? "PASS — extractor output is tautologically identical to raw tracker events"
                : "FAIL — " + totalMismatches + " replays have mismatches");

        assertThat(processed).as("processed replays").isGreaterThan(0);
        assertThat(totalMismatches).as("replays with mismatches").isZero();
    }

    private void compareCounts(String category, Map<String, Integer> oracle,
                               Map<String, Integer> extracted, StringBuilder mismatches) {
        var allKeys = new java.util.TreeSet<>(oracle.keySet());
        allKeys.addAll(extracted.keySet());
        for (String key : allKeys) {
            int oc = oracle.getOrDefault(key, 0);
            int ec = extracted.getOrDefault(key, 0);
            if (oc != ec) {
                mismatches.append(String.format("  %s %s: oracle=%d extracted=%d (delta=%+d)%n",
                    category, key, oc, ec, ec - oc));
            }
        }
    }
}
