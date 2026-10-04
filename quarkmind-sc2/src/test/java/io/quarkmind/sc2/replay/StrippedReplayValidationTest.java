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

    private static final Path ORACLE_DIR_4_10_1 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.10.1_restored");
    private static final Path STRIPPED_DIR_4_10_1 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.10.1/replays");

    private static final Path HSC_XXVII_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2025_HomeStory_Cup_XXVII");

    private static final Path IEM_PYEONGCHANG_2018_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2018_IEM_PyeongChang");
    private static final Path ASUS_ROG_2020_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2020_ASUS_ROG_Online");
    private static final Path DREAMHACK_DALLAS_2025_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2025_DreamHack_Dallas");

    static boolean oracleAndOriginalsExist() {
        return directoryHasReplays(ORACLE_DIR) && directoryHasReplays(STRIPPED_DIR);
    }

    static boolean oracleAndOriginalsExist_4_10_1() {
        return directoryHasReplays(ORACLE_DIR_4_10_1) && directoryHasReplays(STRIPPED_DIR_4_10_1);
    }

    static boolean hscXxviiExists() {
        if (!Files.isDirectory(HSC_XXVII_DIR)) return false;
        try (var stream = Files.walk(HSC_XXVII_DIR, 3)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean directoryHasReplays(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleAndOriginalsExist")
    void javaOutputMatchesOracleForDeterministicFeatures() throws Exception {
        var result = validateSplitDirectories(ORACLE_DIR, STRIPPED_DIR, "4.9.3");
        assertThat(result.gameplayAccuracy).as("Gameplay upgrade detection accuracy (4.9.3)")
            .isGreaterThanOrEqualTo(99.0);
        assertAutoSpawnAccuracy(result);
    }

    @Test
    @EnabledIf("oracleAndOriginalsExist_4_10_1")
    void javaOutputMatchesOracle_4_10_1() throws Exception {
        var result = validateSplitDirectories(ORACLE_DIR_4_10_1, STRIPPED_DIR_4_10_1, "4.10.1");
        assertThat(result.gameplayAccuracy).as("Gameplay upgrade detection accuracy (4.10.1)")
            .isGreaterThanOrEqualTo(95.0);
    }

    @Test
    @EnabledIf("hscXxviiExists")
    void javaOutputMatchesOracle_HSC_XXVII() throws Exception {
        var result = validateUnifiedDirectory(HSC_XXVII_DIR, "HSC_XXVII (baseBuild=94137)");
        assertThat(result.gameplayAccuracy).as("Gameplay upgrade detection accuracy (HSC XXVII)")
            .isGreaterThanOrEqualTo(95.0);
    }

    @Test
    void javaOutputMatchesOracle_IEM_PyeongChang_2018() throws Exception {
        if (!directoryHasReplaysDeep(IEM_PYEONGCHANG_2018_DIR)) return;
        var result = validateUnifiedDirectory(IEM_PYEONGCHANG_2018_DIR, "IEM_PyeongChang_2018 (baseBuild=60321)");
        System.out.printf("IEM PyeongChang 2018 gameplay accuracy: %.1f%%%n", result.gameplayAccuracy);
    }

    @Test
    void javaOutputMatchesOracle_ASUS_ROG_2020() throws Exception {
        if (!directoryHasReplaysDeep(ASUS_ROG_2020_DIR)) return;
        var result = validateUnifiedDirectory(ASUS_ROG_2020_DIR, "ASUS_ROG_2020 (baseBuild=82457)");
        System.out.printf("ASUS ROG 2020 gameplay accuracy: %.1f%%%n", result.gameplayAccuracy);
    }

    @Test
    void javaOutputMatchesOracle_DreamHack_Dallas_2025() throws Exception {
        if (!directoryHasReplaysDeep(DREAMHACK_DALLAS_2025_DIR)) return;
        var result = validateUnifiedDirectory(DREAMHACK_DALLAS_2025_DIR, "DreamHack_Dallas_2025 (baseBuild=93333)");
        System.out.printf("DreamHack Dallas 2025 gameplay accuracy: %.1f%%%n", result.gameplayAccuracy);
    }

    private static boolean directoryHasReplaysDeep(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir, 5)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    private ValidationResult validateSplitDirectories(Path oracleDir, Path strippedDir, String label) throws Exception {
        var extractor = new StrippedReplayFeatureExtractor();
        var stats = new ValidationStats();

        try (var oracleStream = Files.list(oracleDir)) {
            for (Path oracleReplay : oracleStream
                    .filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted()
                    .toList()) {
                Path stripped = strippedDir.resolve(oracleReplay.getFileName());
                if (!Files.exists(stripped)) continue;

                processReplayPair(extractor, oracleReplay, stripped, stats);
            }
        }

        return reportAndAssert(stats, label);
    }

    private ValidationResult validateUnifiedDirectory(Path replayDir, String label) throws Exception {
        var extractor = new StrippedReplayFeatureExtractor();
        var stats = new ValidationStats();

        List<Path> replays;
        try (var stream = Files.walk(replayDir, 5)) {
            replays = stream
                .filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted()
                .toList();
        }

        for (Path replay : replays) {
            processReplayPair(extractor, replay, replay, stats);
        }

        return reportAndAssert(stats, label);
    }

    private void processReplayPair(StrippedReplayFeatureExtractor extractor,
                                   Path oracleReplay, Path extractionReplay,
                                   ValidationStats stats) {
        stats.totalReplays++;

        Map<String, Object> javaJson;
        try {
            javaJson = extractor.extract(extractionReplay);
        } catch (Exception e) {
            System.out.printf("SKIP %s — extract failed: %s%n",
                oracleReplay.getFileName(), e.getMessage());
            return;
        }

        Replay oracleRep = RepParserEngine.parseReplay(oracleReplay,
            EnumSet.of(RepContent.TRACKER_EVENTS));
        if (oracleRep == null || oracleRep.trackerEvents == null) {
            System.out.printf("SKIP %s — no tracker events in oracle%n",
                oracleReplay.getFileName());
            return;
        }

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

        int replayOracleBorn = 0;
        int replayJavaBorn = 0;

        for (String key : oracleBorn.keySet()) {
            int oracle = oracleBorn.getOrDefault(key, 0);
            int java = javaBorn.getOrDefault(key, 0);
            replayOracleBorn += oracle;
            replayJavaBorn += java;
            stats.unitBornDivergence.computeIfAbsent(key, k -> new int[2]);
            stats.unitBornDivergence.get(key)[0] += oracle;
            stats.unitBornDivergence.get(key)[1] += java;
        }
        for (String key : javaBorn.keySet()) {
            if (!oracleBorn.containsKey(key)) {
                stats.unitBornDivergence.computeIfAbsent(key, k -> new int[2]);
                stats.unitBornDivergence.get(key)[1] += javaBorn.get(key);
                replayJavaBorn += javaBorn.get(key);
            }
        }

        stats.totalOracleUnitBorn += replayOracleBorn;
        stats.totalJavaUnitBorn += replayJavaBorn;

        for (String key : oracleInit.keySet()) {
            stats.unitInitDivergence.computeIfAbsent(key, k -> new int[2]);
            stats.unitInitDivergence.get(key)[0] += oracleInit.getOrDefault(key, 0);
            stats.unitInitDivergence.get(key)[1] += javaInit.getOrDefault(key, 0);
            stats.totalOracleUnitInit += oracleInit.get(key);
            stats.totalJavaUnitInit += javaInit.getOrDefault(key, 0);
        }

        for (String key : oracleUpgrade.keySet()) {
            stats.upgradeDivergence.computeIfAbsent(key, k -> new int[2]);
            stats.upgradeDivergence.get(key)[0] += oracleUpgrade.getOrDefault(key, 0);
            stats.upgradeDivergence.get(key)[1] += javaUpgrade.getOrDefault(key, 0);
        }

        stats.passedReplays++;
    }

    private ValidationResult reportAndAssert(ValidationStats stats, String label) {
        System.out.printf("%n=== %s: UnitBorn Divergence (oracle vs java) ===%n", label);
        System.out.printf("Total: oracle=%d java=%d (coverage=%.1f%%)%n",
            stats.totalOracleUnitBorn, stats.totalJavaUnitBorn,
            stats.totalOracleUnitBorn > 0 ? 100.0 * stats.totalJavaUnitBorn / stats.totalOracleUnitBorn : 0);
        for (var entry : stats.unitBornDivergence.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            if (oracle != java) {
                System.out.printf("  %-40s oracle=%4d java=%4d diff=%+d%n",
                    entry.getKey(), oracle, java, java - oracle);
            }
        }

        System.out.printf("%n=== %s: UnitInit Divergence (oracle vs java) ===%n", label);
        System.out.printf("Total: oracle=%d java=%d (coverage=%.1f%%)%n",
            stats.totalOracleUnitInit, stats.totalJavaUnitInit,
            stats.totalOracleUnitInit > 0 ? 100.0 * stats.totalJavaUnitInit / stats.totalOracleUnitInit : 0);
        for (var entry : stats.unitInitDivergence.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            if (oracle != java) {
                System.out.printf("  %-40s oracle=%4d java=%4d diff=%+d%n",
                    entry.getKey(), oracle, java, java - oracle);
            }
        }

        System.out.printf("%n=== %s: Upgrade Divergence (oracle vs java) ===%n", label);
        for (var entry : stats.upgradeDivergence.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            String marker = oracle == java ? "✓" : "✗";
            System.out.printf("  %s %-40s oracle=%4d java=%4d%n",
                marker, entry.getKey(), oracle, java);
        }

        Map<String, int[]> perTypeTotals = new TreeMap<>();
        for (var entry : stats.upgradeDivergence.entrySet()) {
            String upgradeType = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            int[] counts = perTypeTotals.computeIfAbsent(upgradeType, k -> new int[2]);
            counts[0] += entry.getValue()[0];
            counts[1] += entry.getValue()[1];
        }

        var cosmeticPrefixes = List.of("RewardDance", "Spray", "GhostAlternate", "GameHeartActive");
        int totalOracleUpgrades = 0, totalDetectedUpgrades = 0;
        int gameplayOracle = 0, gameplayDetected = 0;
        System.out.printf("%n=== %s: Per-Upgrade-Type Accuracy ===%n", label);
        System.out.printf("%-45s %6s %6s %6s %8s%n", "UpgradeType", "Oracle", "Java", "Missed", "Accuracy");
        System.out.println("-".repeat(75));
        for (var entry : perTypeTotals.entrySet()) {
            int oracle = entry.getValue()[0];
            int java = entry.getValue()[1];
            int missed = oracle - java;
            double accuracy = oracle > 0 ? 100.0 * Math.min(java, oracle) / oracle : 100.0;
            totalOracleUpgrades += oracle;
            totalDetectedUpgrades += java;
            boolean cosmetic = cosmeticPrefixes.stream().anyMatch(p -> entry.getKey().startsWith(p));
            if (!cosmetic) {
                gameplayOracle += oracle;
                gameplayDetected += Math.min(java, oracle);
            }
            String marker = missed == 0 ? "✓" : "✗";
            String tag = cosmetic ? " [cosmetic]" : "";
            System.out.printf("%s %-43s %6d %6d %6d %7.1f%%%s%n",
                marker, entry.getKey(), oracle, java, missed, accuracy, tag);
        }
        System.out.println("-".repeat(75));
        double overallUpgradeAccuracy = totalOracleUpgrades > 0
            ? 100.0 * totalDetectedUpgrades / totalOracleUpgrades : 100.0;
        System.out.printf("  %-43s %6d %6d %6d %7.1f%%%n",
            "TOTAL (all)", totalOracleUpgrades, totalDetectedUpgrades,
            totalOracleUpgrades - totalDetectedUpgrades, overallUpgradeAccuracy);
        double gameplayAccuracy = gameplayOracle > 0 ? 100.0 * gameplayDetected / gameplayOracle : 100.0;
        System.out.printf("  %-43s %6d %6d %6d %7.1f%%%n",
            "TOTAL (gameplay only)", gameplayOracle, gameplayDetected,
            gameplayOracle - gameplayDetected, gameplayAccuracy);
        System.out.println("=================================");

        System.out.printf("%nProcessed %d/%d replays successfully%n", stats.passedReplays, stats.totalReplays);

        assertThat(stats.passedReplays).as("All oracle replays must be processable")
            .isEqualTo(stats.totalReplays);

        assertThat(stats.totalJavaUnitBorn).as("Java must produce UnitBorn events")
            .isGreaterThan(0);
        assertThat(stats.totalJavaUnitInit).as("Java must produce UnitInit events")
            .isGreaterThan(0);

        return new ValidationResult(gameplayAccuracy, stats);
    }

    private void assertAutoSpawnAccuracy(ValidationResult result) {
        var stats = result.stats;

        int oracleLarvaTotal = stats.unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Larva"))
            .mapToInt(e -> e.getValue()[0]).sum();
        int javaLarvaTotal = stats.unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Larva"))
            .mapToInt(e -> e.getValue()[1]).sum();
        if (oracleLarvaTotal > 0) {
            double larvaRatio = (double) javaLarvaTotal / oracleLarvaTotal;
            System.out.printf("%nLarva accuracy: %.1f%% (java=%d, oracle=%d)%n",
                larvaRatio * 100, javaLarvaTotal, oracleLarvaTotal);
            assertThat(larvaRatio).as("Larva count within 20%% of oracle")
                .isBetween(0.8, 1.2);
        }

        int oracleMuleTotal = stats.unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":MULE"))
            .mapToInt(e -> e.getValue()[0]).sum();
        int javaMuleTotal = stats.unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":MULE"))
            .mapToInt(e -> e.getValue()[1]).sum();
        System.out.printf("MULE accuracy: java=%d, oracle=%d%n", javaMuleTotal, oracleMuleTotal);

        int oracleInterceptorTotal = stats.unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Interceptor"))
            .mapToInt(e -> e.getValue()[0]).sum();
        int javaInterceptorTotal = stats.unitBornDivergence.entrySet().stream()
            .filter(e -> e.getKey().contains(":Interceptor"))
            .mapToInt(e -> e.getValue()[1]).sum();
        System.out.printf("Interceptor accuracy: java=%d, oracle=%d (bidirectional divergence expected)%n",
            javaInterceptorTotal, oracleInterceptorTotal);
    }

    private static class ValidationStats {
        int totalReplays = 0;
        int passedReplays = 0;
        Map<String, int[]> unitBornDivergence = new TreeMap<>();
        Map<String, int[]> unitInitDivergence = new TreeMap<>();
        Map<String, int[]> upgradeDivergence = new TreeMap<>();
        int totalOracleUnitBorn = 0;
        int totalJavaUnitBorn = 0;
        int totalOracleUnitInit = 0;
        int totalJavaUnitInit = 0;
    }

    private record ValidationResult(double gameplayAccuracy, ValidationStats stats) {}
}
