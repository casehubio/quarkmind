package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class ReconstitutionDeltaReportTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path RECONSTITUTED_DIR = Path.of(
        "../quarkmind-classifier/data/reconstituted/blizzard_ladder_4.9.3");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static boolean oracleAndReconstitutedExist() {
        return directoryHasReplays(ORACLE_DIR) && Files.isDirectory(RECONSTITUTED_DIR);
    }

    private static boolean directoryHasReplays(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleAndReconstitutedExist")
    void reconstitutionDeltaReport() throws Exception {
        List<Path> oracleReplays;
        try (var s = Files.list(ORACLE_DIR)) {
            oracleReplays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        var trackerExtractor = new TrackerEventFeatureExtractor();
        int matched = 0, skipped = 0;

        var unitBornDelta = new TreeMap<String, int[]>();
        var unitInitDelta = new TreeMap<String, int[]>();
        var upgradeDelta = new TreeMap<String, int[]>();
        int totalOldUnitBorn = 0, totalNewUnitBorn = 0;
        int totalOldUnitInit = 0, totalNewUnitInit = 0;
        int totalOldUpgrade = 0, totalNewUpgrade = 0;
        int totalOldPlayerStats = 0, totalNewPlayerStats = 0;

        for (Path oracleReplay : oracleReplays) {
            String hash = md5(oracleReplay.getFileName().toString());
            Path reconFile = RECONSTITUTED_DIR.resolve(hash + ".json");
            if (!Files.exists(reconFile)) {
                skipped++;
                continue;
            }

            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(oracleReplay,
                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {
                skipped++;
                continue;
            }
            if (replay == null || replay.trackerEvents == null) {
                skipped++;
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> oldJson = MAPPER.readValue(reconFile.toFile(), Map.class);
            Map<String, Object> newJson = trackerExtractor.extract(replay);

            var oldCounts = countEvents(oldJson);
            var newCounts = countEvents(newJson);

            for (var key : mergeKeys(oldCounts.unitBorn, newCounts.unitBorn)) {
                int oc = oldCounts.unitBorn.getOrDefault(key, 0);
                int nc = newCounts.unitBorn.getOrDefault(key, 0);
                unitBornDelta.computeIfAbsent(key, k -> new int[2]);
                unitBornDelta.get(key)[0] += oc;
                unitBornDelta.get(key)[1] += nc;
                totalOldUnitBorn += oc;
                totalNewUnitBorn += nc;
            }
            for (var key : mergeKeys(oldCounts.unitInit, newCounts.unitInit)) {
                int oc = oldCounts.unitInit.getOrDefault(key, 0);
                int nc = newCounts.unitInit.getOrDefault(key, 0);
                unitInitDelta.computeIfAbsent(key, k -> new int[2]);
                unitInitDelta.get(key)[0] += oc;
                unitInitDelta.get(key)[1] += nc;
                totalOldUnitInit += oc;
                totalNewUnitInit += nc;
            }
            for (var key : mergeKeys(oldCounts.upgrade, newCounts.upgrade)) {
                int oc = oldCounts.upgrade.getOrDefault(key, 0);
                int nc = newCounts.upgrade.getOrDefault(key, 0);
                upgradeDelta.computeIfAbsent(key, k -> new int[2]);
                upgradeDelta.get(key)[0] += oc;
                upgradeDelta.get(key)[1] += nc;
                totalOldUpgrade += oc;
                totalNewUpgrade += nc;
            }
            totalOldPlayerStats += oldCounts.playerStats;
            totalNewPlayerStats += newCounts.playerStats;

            matched++;
        }

        System.out.println("\n=== Reconstitution Delta Report ===");
        System.out.println("Old: StrippedReplayFeatureExtractor (CmdEvent-based)");
        System.out.println("New: TrackerEventFeatureExtractor (ground truth)\n");
        System.out.printf("Replays: %d matched, %d skipped%n%n", matched, skipped);

        System.out.println("--- Aggregate ---");
        System.out.printf("%-15s %10s %10s %10s  %s%n", "Category", "Old", "New", "Delta", "Change%");
        System.out.println("-".repeat(70));
        printAggregate("UnitBorn", totalOldUnitBorn, totalNewUnitBorn);
        printAggregate("UnitInit", totalOldUnitInit, totalNewUnitInit);
        printAggregate("Upgrade", totalOldUpgrade, totalNewUpgrade);
        printAggregate("PlayerStats", totalOldPlayerStats, totalNewPlayerStats);

        System.out.println("\n--- UnitBorn Per-Type Delta (top 20 by abs delta) ---");
        System.out.printf("%-30s %8s %8s %8s%n", "Type", "Old", "New", "Delta");
        System.out.println("-".repeat(60));
        printTopDelta(unitBornDelta, 20);

        System.out.println("\n--- UnitInit Per-Type Delta (top 20 by abs delta) ---");
        System.out.printf("%-30s %8s %8s %8s%n", "Type", "Old", "New", "Delta");
        System.out.println("-".repeat(60));
        printTopDelta(unitInitDelta, 20);

        System.out.println("\n--- Upgrade Per-Type Delta ---");
        System.out.printf("%-40s %8s %8s %8s%n", "Type", "Old", "New", "Delta");
        System.out.println("-".repeat(65));
        printTopDelta(upgradeDelta, 50);

        System.out.printf("%nTraining data impact: switching to tracker-based extraction changes " +
            "UnitBorn counts by %+d (%.1f%%), UnitInit by %+d (%.1f%%), Upgrades by %+d (%.1f%%)%n",
            totalNewUnitBorn - totalOldUnitBorn,
            totalOldUnitBorn > 0 ? 100.0 * (totalNewUnitBorn - totalOldUnitBorn) / totalOldUnitBorn : 0,
            totalNewUnitInit - totalOldUnitInit,
            totalOldUnitInit > 0 ? 100.0 * (totalNewUnitInit - totalOldUnitInit) / totalOldUnitInit : 0,
            totalNewUpgrade - totalOldUpgrade,
            totalOldUpgrade > 0 ? 100.0 * (totalNewUpgrade - totalOldUpgrade) / totalOldUpgrade : 0);

        assertThat(matched).as("matched replays").isGreaterThan(0);
    }

    private static void printAggregate(String category, int old, int newVal) {
        int delta = newVal - old;
        double pct = old > 0 ? 100.0 * delta / old : 0;
        System.out.printf("%-15s %10d %10d %+10d  %+.1f%%%n", category, old, newVal, delta, pct);
    }

    private static void printTopDelta(Map<String, int[]> delta, int limit) {
        delta.entrySet().stream()
            .filter(e -> e.getValue()[0] != e.getValue()[1])
            .sorted((a, b) -> Integer.compare(
                Math.abs(b.getValue()[1] - b.getValue()[0]),
                Math.abs(a.getValue()[1] - a.getValue()[0])))
            .limit(limit)
            .forEach(e -> System.out.printf("%-40s %8d %8d %+8d%n",
                e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[1] - e.getValue()[0]));
    }

    private static Set<String> mergeKeys(Map<String, Integer> a, Map<String, Integer> b) {
        var keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static EventCounts countEvents(Map<String, Object> json) {
        var counts = new EventCounts();
        List<Map<String, Object>> events = (List<Map<String, Object>>) json.get("trackerEvents");
        if (events == null) return counts;
        for (Map<String, Object> e : events) {
            String evtType = (String) e.get("evtTypeName");
            if (evtType == null) continue;
            switch (evtType) {
                case "UnitBorn" -> {
                    int pid = ((Number) e.get("controlPlayerId")).intValue();
                    String key = "P" + pid + ":" + e.get("unitTypeName");
                    counts.unitBorn.merge(key, 1, Integer::sum);
                }
                case "UnitInit" -> {
                    int pid = ((Number) e.get("controlPlayerId")).intValue();
                    String key = "P" + pid + ":" + e.get("unitTypeName");
                    counts.unitInit.merge(key, 1, Integer::sum);
                }
                case "Upgrade" -> {
                    int pid = ((Number) e.getOrDefault("playerId", e.get("controlPlayerId"))).intValue();
                    String key = "P" + pid + ":" + e.get("upgradeTypeName");
                    counts.upgrade.merge(key, 1, Integer::sum);
                }
                case "PlayerStats" -> counts.playerStats++;
            }
        }
        return counts;
    }

    private static class EventCounts {
        Map<String, Integer> unitBorn = new TreeMap<>();
        Map<String, Integer> unitInit = new TreeMap<>();
        Map<String, Integer> upgrade = new TreeMap<>();
        int playerStats;
    }

    private static String md5(String input) throws Exception {
        var md = MessageDigest.getInstance("MD5");
        var bytes = md.digest(input.getBytes());
        var sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
