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
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class RestorationFidelityTest {

    private static final Path ORIGINAL_DIR = Path.of(
        "replays/aiarena_protoss");
    private static final Path RESTORED_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/aiarena_protoss_restored");

    static boolean bothDirsExist() {
        return directoryHasReplays(ORIGINAL_DIR) && directoryHasReplays(RESTORED_DIR);
    }

    private static boolean directoryHasReplays(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("bothDirsExist")
    void restoredTrackerEventsMatchOriginals() throws Exception {
        List<Path> originals;
        try (var s = Files.list(ORIGINAL_DIR)) {
            originals = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        var extractor = new TrackerEventFeatureExtractor();
        int compared = 0, identical = 0, divergent = 0, skipped = 0;

        var unitBornDelta = new TreeMap<String, int[]>();
        var unitInitDelta = new TreeMap<String, int[]>();
        var upgradeDelta = new TreeMap<String, int[]>();
        int totalOrigPlayerStats = 0, totalRestoredPlayerStats = 0;
        var perReplayResults = new TreeMap<String, String>();

        for (Path originalPath : originals) {
            Path restoredPath = RESTORED_DIR.resolve(originalPath.getFileName());
            if (!Files.exists(restoredPath)) {
                skipped++;
                continue;
            }

            Replay original, restored;
            try {
                original = RepParserEngine.parseReplay(originalPath,
                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                restored = RepParserEngine.parseReplay(restoredPath,
                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {
                skipped++;
                continue;
            }

            if (original == null || original.trackerEvents == null
                || restored == null || restored.trackerEvents == null) {
                skipped++;
                continue;
            }

            Map<String, Object> origJson = extractor.extract(original);
            Map<String, Object> restJson = extractor.extract(restored);

            var origCounts = countByCategory(origJson);
            var restCounts = countByCategory(restJson);

            compared++;
            var mismatches = new StringBuilder();

            compareMaps("UnitBorn", origCounts.unitBorn, restCounts.unitBorn,
                unitBornDelta, mismatches);
            compareMaps("UnitInit", origCounts.unitInit, restCounts.unitInit,
                unitInitDelta, mismatches);
            compareMaps("Upgrade", origCounts.upgrade, restCounts.upgrade,
                upgradeDelta, mismatches);

            totalOrigPlayerStats += origCounts.playerStats;
            totalRestoredPlayerStats += restCounts.playerStats;
            if (origCounts.playerStats != restCounts.playerStats) {
                mismatches.append(String.format("  PlayerStats: orig=%d restored=%d%n",
                    origCounts.playerStats, restCounts.playerStats));
            }

            if (mismatches.isEmpty()) {
                identical++;
                perReplayResults.put(originalPath.getFileName().toString(), "IDENTICAL");
            } else {
                divergent++;
                perReplayResults.put(originalPath.getFileName().toString(), mismatches.toString());
            }
        }

        System.out.println("\n=== Docker Restoration Fidelity — AI Arena Replays ===\n");
        System.out.printf("Replays: %d compared, %d identical, %d divergent, %d skipped%n%n",
            compared, identical, divergent, skipped);

        System.out.println("--- Per-Replay Results ---");
        for (var entry : perReplayResults.entrySet()) {
            if ("IDENTICAL".equals(entry.getValue())) {
                System.out.printf("  %s: IDENTICAL%n", entry.getKey());
            } else {
                System.out.printf("  %s: DIVERGENT%n%s", entry.getKey(), entry.getValue());
            }
        }

        if (!unitBornDelta.isEmpty()) {
            System.out.println("\n--- UnitBorn Aggregate Delta ---");
            printDelta(unitBornDelta);
        }
        if (!unitInitDelta.isEmpty()) {
            System.out.println("\n--- UnitInit Aggregate Delta ---");
            printDelta(unitInitDelta);
        }
        if (!upgradeDelta.isEmpty()) {
            System.out.println("\n--- Upgrade Aggregate Delta ---");
            printDelta(upgradeDelta);
        }

        System.out.printf("%nPlayerStats: orig=%d restored=%d delta=%+d%n",
            totalOrigPlayerStats, totalRestoredPlayerStats,
            totalRestoredPlayerStats - totalOrigPlayerStats);

        System.out.printf("%nVerdict: %s%n",
            divergent == 0 ? "PASS — restored tracker events are identical to originals"
                : "FAIL — " + divergent + " replays have divergent tracker events");

        assertThat(compared).as("compared replays").isGreaterThan(0);
        assertThat(divergent).as("divergent replays").isZero();
    }

    private void compareMaps(String category, Map<String, Integer> orig,
                             Map<String, Integer> restored, Map<String, int[]> deltaSink,
                             StringBuilder mismatches) {
        var allKeys = new java.util.TreeSet<>(orig.keySet());
        allKeys.addAll(restored.keySet());
        for (String key : allKeys) {
            int oc = orig.getOrDefault(key, 0);
            int rc = restored.getOrDefault(key, 0);
            if (oc != rc) {
                mismatches.append(String.format("  %s %s: orig=%d restored=%d (delta=%+d)%n",
                    category, key, oc, rc, rc - oc));
                deltaSink.computeIfAbsent(key, k -> new int[2]);
                deltaSink.get(key)[0] += oc;
                deltaSink.get(key)[1] += rc;
            }
        }
    }

    private static void printDelta(Map<String, int[]> delta) {
        System.out.printf("%-40s %8s %8s %8s%n", "Type", "Orig", "Restored", "Delta");
        delta.entrySet().stream()
            .sorted((a, b) -> Integer.compare(
                Math.abs(b.getValue()[1] - b.getValue()[0]),
                Math.abs(a.getValue()[1] - a.getValue()[0])))
            .forEach(e -> System.out.printf("%-40s %8d %8d %+8d%n",
                e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[1] - e.getValue()[0]));
    }

    @SuppressWarnings("unchecked")
    private static CategoryCounts countByCategory(Map<String, Object> json) {
        var counts = new CategoryCounts();
        List<Map<String, Object>> events = (List<Map<String, Object>>) json.get("trackerEvents");
        for (Map<String, Object> e : events) {
            String evtType = (String) e.get("evtTypeName");
            switch (evtType) {
                case "UnitBorn" -> {
                    int pid = ((Number) e.get("controlPlayerId")).intValue();
                    counts.unitBorn.merge("P" + pid + ":" + e.get("unitTypeName"), 1, Integer::sum);
                }
                case "UnitInit" -> {
                    int pid = ((Number) e.get("controlPlayerId")).intValue();
                    counts.unitInit.merge("P" + pid + ":" + e.get("unitTypeName"), 1, Integer::sum);
                }
                case "Upgrade" -> {
                    int pid = ((Number) e.get("playerId")).intValue();
                    counts.upgrade.merge("P" + pid + ":" + e.get("upgradeTypeName"), 1, Integer::sum);
                }
                case "PlayerStats" -> counts.playerStats++;
            }
        }
        return counts;
    }

    private static class CategoryCounts {
        Map<String, Integer> unitBorn = new TreeMap<>();
        Map<String, Integer> unitInit = new TreeMap<>();
        Map<String, Integer> upgrade = new TreeMap<>();
        int playerStats;
    }
}
