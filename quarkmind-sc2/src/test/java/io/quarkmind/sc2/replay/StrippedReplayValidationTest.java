package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class StrippedReplayValidationTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path STRIPPED_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    static boolean oracleAndOriginalsExist() {
        if (!Files.isDirectory(ORACLE_DIR) || !Files.isDirectory(STRIPPED_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleAndOriginalsExist")
    void javaOutputMatchesOracleForDeterministicFeatures() throws Exception {
        var extractor = new StrippedReplayFeatureExtractor();
        int totalReplays = 0;
        int passedReplays = 0;

        // Aggregate divergence stats
        Map<String, int[]> unitBornDivergence = new TreeMap<>();
        Map<String, int[]> unitInitDivergence = new TreeMap<>();
        Map<String, int[]> upgradeDivergence = new TreeMap<>();
        int totalOracleUnitBorn = 0;
        int totalJavaUnitBorn = 0;
        int totalOracleUnitInit = 0;
        int totalJavaUnitInit = 0;

        try (var oracleStream = Files.list(ORACLE_DIR)) {
            for (Path oracleReplay : oracleStream
                    .filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted()
                    .toList()) {
                Path stripped = STRIPPED_DIR.resolve(oracleReplay.getFileName());
                if (!Files.exists(stripped)) continue;

                totalReplays++;

                Map<String, Object> javaJson;
                try {
                    javaJson = extractor.extract(stripped);
                } catch (Exception e) {
                    System.out.printf("SKIP %s — extract failed: %s%n",
                        oracleReplay.getFileName(), e.getMessage());
                    continue;
                }

                Replay oracleRep = RepParserEngine.parseReplay(oracleReplay,
                    EnumSet.of(RepContent.TRACKER_EVENTS));
                if (oracleRep == null || oracleRep.trackerEvents == null) {
                    System.out.printf("SKIP %s — no tracker events in oracle%n",
                        oracleReplay.getFileName());
                    continue;
                }

                // Extract oracle ground truth counts per (player, type)
                Map<String, Integer> oracleBorn = new TreeMap<>();
                Map<String, Integer> oracleInit = new TreeMap<>();
                Map<String, Integer> oracleUpgrade = new TreeMap<>();

                for (Event raw : oracleRep.trackerEvents.getEvents()) {
                    switch (raw.getId()) {
                        case ITrackerEvents.ID_UNIT_BORN -> {
                            IBaseUnitEvent born = (IBaseUnitEvent) raw;
                            if (born.getControlPlayerId() == null || born.getControlPlayerId() == 0) continue;
                            if (born.getLoop() == 0) continue;
                            String key = "P" + born.getControlPlayerId() + ":" + born.getUnitTypeName();
                            oracleBorn.merge(key, 1, Integer::sum);
                        }
                        case ITrackerEvents.ID_UNIT_INIT -> {
                            IBaseUnitEvent init = (IBaseUnitEvent) raw;
                            if (init.getControlPlayerId() == null || init.getControlPlayerId() == 0) continue;
                            String key = "P" + init.getControlPlayerId() + ":" + init.getUnitTypeName();
                            oracleInit.merge(key, 1, Integer::sum);
                        }
                        case ITrackerEvents.ID_UPGRADE -> {
                            IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                            if (upgrade.getPlayerId() == null) continue;
                            String key = "P" + upgrade.getPlayerId() + ":" + upgrade.getUpgradeTypeName();
                            oracleUpgrade.merge(key, 1, Integer::sum);
                        }
                    }
                }

                // Extract Java pipeline counts
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> javaEvents =
                    (List<Map<String, Object>>) javaJson.get("trackerEvents");

                Map<String, Integer> javaBorn = new TreeMap<>();
                Map<String, Integer> javaInit = new TreeMap<>();
                Map<String, Integer> javaUpgrade = new TreeMap<>();

                for (Map<String, Object> e : javaEvents) {
                    String evtType = (String) e.get("evtTypeName");
                    int pid = e.containsKey("controlPlayerId")
                        ? ((Number) e.get("controlPlayerId")).intValue()
                        : ((Number) e.getOrDefault("playerId", 0)).intValue();
                    if (pid == 0) continue;

                    switch (evtType) {
                        case "UnitBorn" -> {
                            String key = "P" + pid + ":" + e.get("unitTypeName");
                            javaBorn.merge(key, 1, Integer::sum);
                        }
                        case "UnitInit" -> {
                            String key = "P" + pid + ":" + e.get("unitTypeName");
                            javaInit.merge(key, 1, Integer::sum);
                        }
                        case "Upgrade" -> {
                            String key = "P" + pid + ":" + e.get("upgradeTypeName");
                            javaUpgrade.merge(key, 1, Integer::sum);
                        }
                    }
                }

                // Accumulate per-type divergence
                boolean replayPassed = true;
                int replayOracleBorn = 0;
                int replayJavaBorn = 0;

                for (String key : oracleBorn.keySet()) {
                    int oracle = oracleBorn.getOrDefault(key, 0);
                    int java = javaBorn.getOrDefault(key, 0);
                    replayOracleBorn += oracle;
                    replayJavaBorn += java;
                    unitBornDivergence.computeIfAbsent(key, k -> new int[2]);
                    unitBornDivergence.get(key)[0] += oracle;
                    unitBornDivergence.get(key)[1] += java;
                }
                for (String key : javaBorn.keySet()) {
                    if (!oracleBorn.containsKey(key)) {
                        unitBornDivergence.computeIfAbsent(key, k -> new int[2]);
                        unitBornDivergence.get(key)[1] += javaBorn.get(key);
                        replayJavaBorn += javaBorn.get(key);
                    }
                }

                totalOracleUnitBorn += replayOracleBorn;
                totalJavaUnitBorn += replayJavaBorn;

                for (String key : oracleInit.keySet()) {
                    unitInitDivergence.computeIfAbsent(key, k -> new int[2]);
                    unitInitDivergence.get(key)[0] += oracleInit.getOrDefault(key, 0);
                    unitInitDivergence.get(key)[1] += javaInit.getOrDefault(key, 0);
                    totalOracleUnitInit += oracleInit.get(key);
                    totalJavaUnitInit += javaInit.getOrDefault(key, 0);
                }

                for (String key : oracleUpgrade.keySet()) {
                    upgradeDivergence.computeIfAbsent(key, k -> new int[2]);
                    upgradeDivergence.get(key)[0] += oracleUpgrade.getOrDefault(key, 0);
                    upgradeDivergence.get(key)[1] += javaUpgrade.getOrDefault(key, 0);
                }

                passedReplays++;
            }
        }

        // Print divergence report
        System.out.println("\n=== UnitBorn Divergence (oracle vs java) ===");
        System.out.printf("Total: oracle=%d java=%d (coverage=%.1f%%)%n",
            totalOracleUnitBorn, totalJavaUnitBorn,
            totalOracleUnitBorn > 0 ? 100.0 * totalJavaUnitBorn / totalOracleUnitBorn : 0);
        for (var entry : unitBornDivergence.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            if (oracle != java) {
                System.out.printf("  %-40s oracle=%4d java=%4d diff=%+d%n",
                    entry.getKey(), oracle, java, java - oracle);
            }
        }

        System.out.println("\n=== UnitInit Divergence (oracle vs java) ===");
        System.out.printf("Total: oracle=%d java=%d (coverage=%.1f%%)%n",
            totalOracleUnitInit, totalJavaUnitInit,
            totalOracleUnitInit > 0 ? 100.0 * totalJavaUnitInit / totalOracleUnitInit : 0);
        for (var entry : unitInitDivergence.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            if (oracle != java) {
                System.out.printf("  %-40s oracle=%4d java=%4d diff=%+d%n",
                    entry.getKey(), oracle, java, java - oracle);
            }
        }

        System.out.println("\n=== Upgrade Divergence (oracle vs java) ===");
        for (var entry : upgradeDivergence.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            String marker = oracle == java ? "✓" : "✗";
            System.out.printf("  %s %-40s oracle=%4d java=%4d%n",
                marker, entry.getKey(), oracle, java);
        }

        System.out.printf("%nProcessed %d/%d replays successfully%n", passedReplays, totalReplays);

        assertThat(passedReplays).as("All oracle replays must be processable")
            .isEqualTo(totalReplays);

        assertThat(totalJavaUnitBorn).as("Java must produce UnitBorn events")
            .isGreaterThan(0);
        assertThat(totalJavaUnitInit).as("Java must produce UnitInit events")
            .isGreaterThan(0);

        // Auto-spawn accuracy assertions (#324)
        int oracleLarvaTotal = unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Larva"))
            .mapToInt(e -> e.getValue()[0]).sum();
        int javaLarvaTotal = unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Larva"))
            .mapToInt(e -> e.getValue()[1]).sum();
        if (oracleLarvaTotal > 0) {
            double larvaRatio = (double) javaLarvaTotal / oracleLarvaTotal;
            System.out.printf("%nLarva accuracy: %.1f%% (java=%d, oracle=%d)%n",
                larvaRatio * 100, javaLarvaTotal, oracleLarvaTotal);
            assertThat(larvaRatio).as("Larva count within 20%% of oracle")
                .isBetween(0.8, 1.2);
        }

        int oracleMuleTotal = unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":MULE"))
            .mapToInt(e -> e.getValue()[0]).sum();
        int javaMuleTotal = unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":MULE"))
            .mapToInt(e -> e.getValue()[1]).sum();
        System.out.printf("MULE accuracy: java=%d, oracle=%d%n", javaMuleTotal, oracleMuleTotal);

        int oracleInterceptorTotal = unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Interceptor"))
            .mapToInt(e -> e.getValue()[0]).sum();
        int javaInterceptorTotal = unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Interceptor"))
            .mapToInt(e -> e.getValue()[1]).sum();
        System.out.printf("Interceptor accuracy: java=%d, oracle=%d (bidirectional divergence expected)%n",
            javaInterceptorTotal, oracleInterceptorTotal);
    }
}
