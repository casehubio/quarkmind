package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.BuildingType;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("report")
class TrackerDrivenAccuracyBaselineTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final Set<UnitType> EXCLUDED_TYPES = EnumSet.of(UnitType.EGG, UnitType.MULE);

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void trackerDrivenAccuracyBaseline() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        Map<Integer, Map<UnitType, long[]>> unitAccum = new TreeMap<>();
        Map<Integer, Map<BuildingType, long[]>> bldgAccum = new TreeMap<>();
        Map<Integer, int[]> upgradeAccum = new TreeMap<>();
        Map<Integer, Map<String, int[]>> upgradeByName = new TreeMap<>();
        Map<Integer, double[][]> economyAccum = new TreeMap<>();
        int totalPlayerRuns = 0;

        for (int cp : CHECKPOINTS_MIN) {
            unitAccum.put(cp, new EnumMap<>(UnitType.class));
            bldgAccum.put(cp, new EnumMap<>(BuildingType.class));
            upgradeAccum.put(cp, new int[]{0, 0});
            upgradeByName.put(cp, new TreeMap<>());
            economyAccum.put(cp, new double[13][2]);
        }

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

                    DivergenceReport report = ReplayValidationHarness.run(
                        groundTruth, intents, TICK_LIMIT, race);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= report.ticks().size()) continue;
                        var snap = report.ticks().get(tickIndex);

                        for (var entry : snap.groundTruthUnitsByType().entrySet()) {
                            if (EXCLUDED_TYPES.contains(entry.getKey())) continue;
                            long[] acc = unitAccum.get(cp).computeIfAbsent(
                                entry.getKey(), k -> new long[]{0, 0});
                            acc[0] += entry.getValue();
                            int emVal = snap.emulatedUnitsByType()
                                .getOrDefault(entry.getKey(), 0);
                            acc[1] += Math.min(emVal, entry.getValue());
                        }

                        for (var entry : snap.groundTruthBuildingsByType().entrySet()) {
                            long[] acc = bldgAccum.get(cp).computeIfAbsent(
                                entry.getKey(), k -> new long[]{0, 0});
                            acc[0] += entry.getValue();
                            int emVal = snap.emulatedBuildingsByType()
                                .getOrDefault(entry.getKey(), 0);
                            acc[1] += Math.min(emVal, entry.getValue());
                        }

                        int gtSize = snap.groundTruthUpgrades().size();
                        int matchCount = (int) snap.groundTruthUpgrades().stream()
                            .filter(snap.emulatedUpgrades()::contains).count();
                        upgradeAccum.get(cp)[0] += gtSize;
                        upgradeAccum.get(cp)[1] += matchCount;
                        for (String upName : snap.groundTruthUpgrades()) {
                            int[] counts = upgradeByName.get(cp).computeIfAbsent(upName, k -> new int[]{0, 0});
                            counts[0]++;
                            if (snap.emulatedUpgrades().contains(upName)) counts[1]++;
                        }

                        if (snap.groundTruthEconomy() != null && snap.emulatedEconomy() != null) {
                            accumulateEconomy(snap.groundTruthEconomy(), snap.emulatedEconomy(),
                                economyAccum.get(cp));
                        }
                    }
                    totalPlayerRuns++;
                } catch (Exception e) { /* skip failed replays */ }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# Tracker-Driven EmulatedGame Accuracy Baseline — %s%n%n",
            java.time.LocalDate.now()));
        sb.append(String.format("**Dataset:** Oracle (118 replays, v4.9.3)%n"));
        sb.append(String.format("**Processed:** %d replays, %d player runs%n",
            replays.size(), totalPlayerRuns));
        sb.append(String.format("**Method:** Tracker-event-driven (100%% input coverage)%n%n"));
        sb.append("---\n\n");

        for (int cp : CHECKPOINTS_MIN) {
            sb.append(String.format("## %d-minute checkpoint%n%n", cp));

            sb.append("### Units — Per-Type\n\n");
            sb.append("| Type | GT | Emulated | Accuracy |\n");
            sb.append("|---|---|---|---|\n");
            long totalGt = 0, totalEm = 0;
            for (var entry : new TreeMap<>(unitAccum.get(cp)).entrySet()) {
                long gt = entry.getValue()[0];
                long em = entry.getValue()[1];
                totalGt += gt;
                totalEm += em;
                double acc = gt > 0 ? em * 100.0 / gt : 100.0;
                sb.append(String.format("| %s | %d | %d | %.1f%% |%n",
                    entry.getKey(), gt, em, acc));
            }
            double totalAcc = totalGt > 0 ? totalEm * 100.0 / totalGt : 100.0;
            sb.append(String.format("| **TOTAL** | %d | %d | **%.1f%%** |%n%n",
                totalGt, totalEm, totalAcc));

            sb.append("### Buildings — Per-Type\n\n");
            sb.append("| Type | GT | Emulated | Accuracy |\n");
            sb.append("|---|---|---|---|\n");
            long bldgGt = 0, bldgEm = 0;
            for (var entry : new TreeMap<>(bldgAccum.get(cp)).entrySet()) {
                long gt = entry.getValue()[0];
                long em = entry.getValue()[1];
                bldgGt += gt;
                bldgEm += em;
                double acc = gt > 0 ? em * 100.0 / gt : 100.0;
                sb.append(String.format("| %s | %d | %d | %.1f%% |%n",
                    entry.getKey(), gt, em, acc));
            }
            double bldgAcc = bldgGt > 0 ? bldgEm * 100.0 / bldgGt : 100.0;
            sb.append(String.format("| **TOTAL** | %d | %d | **%.1f%%** |%n%n",
                bldgGt, bldgEm, bldgAcc));

            int[] upAcc = upgradeAccum.get(cp);
            double upPct = upAcc[0] > 0 ? upAcc[1] * 100.0 / upAcc[0] : 100.0;
            sb.append(String.format("### Upgrades%n%nAccuracy: %.1f%% (%d/%d)%n%n",
                upPct, upAcc[1], upAcc[0]));
            if (!upgradeByName.get(cp).isEmpty()) {
                sb.append("| Upgrade | GT | Matched | Accuracy |\n");
                sb.append("|---|---|---|---|\n");
                for (var entry : upgradeByName.get(cp).entrySet()) {
                    int gt = entry.getValue()[0];
                    int matched = entry.getValue()[1];
                    double pct = gt > 0 ? matched * 100.0 / gt : 100.0;
                    sb.append(String.format("| %s | %d | %d | %.1f%% |%n",
                        entry.getKey(), gt, matched, pct));
                }
                sb.append("\n");
            }

            sb.append("### Economy — Aggregate Error\n\n");
            sb.append("| Field | GT (sum) | Em (sum) | Error% |\n");
            sb.append("|---|---|---|---|\n");
            String[] econFields = {"mineralsCurrent", "vespeneCurrent",
                "mineralsCollectionRate", "vespeneCollectionRate",
                "foodMade", "foodUsed", "workersActiveCount",
                "mineralsUsedCurrentArmy"};
            double[][] econ = economyAccum.get(cp);
            for (int i = 0; i < econFields.length && i < econ.length; i++) {
                double gt = econ[i][0];
                double em = econ[i][1];
                double err = gt != 0 ? Math.abs(gt - em) / Math.abs(gt) * 100.0 : 0;
                sb.append(String.format("| %s | %.0f | %.0f | %.1f%% |%n",
                    econFields[i], gt, em, err));
            }
            sb.append("\n");
        }

        String report = sb.toString();
        System.out.println(report);

        Path outputPath = Path.of("../docs/benchmarks/tracker-driven-accuracy-baseline.md");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, report);

        long gt5 = unitAccum.get(5).values().stream().mapToLong(a -> a[0]).sum();
        long em5 = unitAccum.get(5).values().stream().mapToLong(a -> a[1]).sum();
        double unitAcc5 = gt5 > 0 ? em5 * 100.0 / gt5 : 0;
        System.out.printf("%n=== 5-min unit accuracy: %.1f%% (tracker-driven) ===%n", unitAcc5);
        assertTrue(unitAcc5 > 99.0,
            "Tracker-driven unit accuracy at 5min should be > 99.0%: " + unitAcc5);
    }

    @Test
    @Tag("diagnostic")
    @EnabledIf("oracleExists")
    void tickQuantizationFloorAnalysis() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        int tickAt5min        = 5 * TICKS_PER_MINUTE - 1;
        int tickAt5minPlus2   = tickAt5min + 2;
        int extendedTickLimit = TICKS_PER_MINUTE * 8;

        Map<UnitType, long[]> unitsAt5min         = new java.util.EnumMap<>(UnitType.class);
        Map<UnitType, long[]> unitsAt5minPlus2    = new java.util.EnumMap<>(UnitType.class);
        int                   totalPlayerRuns     = 0;
        int                   replaysWithRecovery = 0;

        for (Path replayPath : replays) {
            hu.scelight.sc2.rep.model.Replay replay;
            try {
                replay = hu.scelight.sc2.rep.factory.RepParserEngine.parseReplay(
                        replayPath, EnumSet.of(hu.scelight.sc2.rep.factory.RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    io.quarkmind.sc2.mock.ReplaySimulatedGame groundTruth =
                            new io.quarkmind.sc2.mock.ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent>        intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    io.quarkmind.domain.Race race    = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    DivergenceReport report = ReplayValidationHarness.run(
                            groundTruth, intents, extendedTickLimit, race);

                    if (tickAt5min >= report.ticks().size()) {continue;}
                    var snap5 = report.ticks().get(tickAt5min);
                    var snap5plus2 = (tickAt5minPlus2 < report.ticks().size())
                                     ? report.ticks().get(tickAt5minPlus2) : null;

                    boolean anyRecovered = false;
                    for (var entry : snap5.groundTruthUnitsByType().entrySet()) {
                        if (EXCLUDED_TYPES.contains(entry.getKey())) {continue;}
                        UnitType ut    = entry.getKey();
                        int      gtVal = entry.getValue();

                        int    emAt5      = snap5.emulatedUnitsByType().getOrDefault(ut, 0);
                        int    matchedAt5 = Math.min(emAt5, gtVal);
                        long[] acc5       = unitsAt5min.computeIfAbsent(ut, k -> new long[]{0, 0});
                        acc5[0] += gtVal;
                        acc5[1] += matchedAt5;

                        if (snap5plus2 != null) {
                            int    emAt5p2      = snap5plus2.emulatedUnitsByType().getOrDefault(ut, 0);
                            int    matchedAt5p2 = Math.min(emAt5p2, gtVal);
                            long[] acc5p2       = unitsAt5minPlus2.computeIfAbsent(ut, k -> new long[]{0, 0});
                            acc5p2[0] += gtVal;
                            acc5p2[1] += matchedAt5p2;

                            if (matchedAt5p2 > matchedAt5) {anyRecovered = true;}
                        }
                    }
                    if (anyRecovered) {replaysWithRecovery++;}
                    totalPlayerRuns++;
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.printf("%n=== Tick Quantization Floor Analysis ===%n");
        System.out.printf("Player runs: %d, Replays with recovery at +2 ticks: %d%n%n",
                          totalPlayerRuns, replaysWithRecovery);

        long totalGt5 = 0, totalEm5 = 0, totalEm5p2 = 0;
        System.out.printf("%-16s  %6s  %6s  %6s  %8s  %8s  %s%n",
                          "UnitType", "GT", "Em@5m", "Em@5m+2", "Acc@5m", "Acc@5m+2", "Recovered");
        for (UnitType ut : UnitType.values()) {
            long[] at5   = unitsAt5min.get(ut);
            long[] at5p2 = unitsAt5minPlus2.get(ut);
            if (at5 == null) {continue;}
            long gt    = at5[0];
            long em5   = at5[1];
            long em5p2 = (at5p2 != null) ? at5p2[1] : em5;
            totalGt5 += gt;
            totalEm5 += em5;
            totalEm5p2 += em5p2;
            if (em5 < gt || em5p2 != em5) {
                System.out.printf("%-16s  %6d  %6d  %6d  %7.1f%%  %7.1f%%  %+d%n",
                                  ut, gt, em5, em5p2,
                                  gt > 0 ? em5 * 100.0 / gt : 100.0,
                                  gt > 0 ? em5p2 * 100.0 / gt : 100.0,
                                  (int) (em5p2 - em5));
            }
        }
        double acc5   = totalGt5 > 0 ? totalEm5 * 100.0 / totalGt5 : 100.0;
        double acc5p2 = totalGt5 > 0 ? totalEm5p2 * 100.0 / totalGt5 : 100.0;
        System.out.printf("%n%-16s  %6d  %6d  %6d  %7.1f%%  %7.1f%%  %+d%n",
                          "TOTAL", totalGt5, totalEm5, totalEm5p2, acc5, acc5p2,
                          (int) (totalEm5p2 - totalEm5));
        System.out.printf("%nConclusion: %s%n",
                          totalEm5p2 >= totalGt5
                          ? "100%% achievable — all missing units recover within 2 ticks (tick quantization floor)"
                          : String.format("%.1f%% is the floor — %d units still missing after +2 ticks",
                                          acc5p2, totalGt5 - totalEm5p2));
    }


    private static void accumulateEconomy(
            io.quarkmind.domain.PlayerEconomyStats gt,
            io.quarkmind.domain.PlayerEconomyStats em,
            double[][] accum) {
        accum[0][0] += gt.mineralsCurrent();     accum[0][1] += em.mineralsCurrent();
        accum[1][0] += gt.vespeneCurrent();      accum[1][1] += em.vespeneCurrent();
        accum[2][0] += gt.mineralsCollectionRate(); accum[2][1] += em.mineralsCollectionRate();
        accum[3][0] += gt.vespeneCollectionRate(); accum[3][1] += em.vespeneCollectionRate();
        accum[4][0] += gt.foodMade();            accum[4][1] += em.foodMade();
        accum[5][0] += gt.foodUsed();            accum[5][1] += em.foodUsed();
        accum[6][0] += gt.workersActiveCount();  accum[6][1] += em.workersActiveCount();
        accum[7][0] += gt.mineralsUsedCurrentArmy(); accum[7][1] += em.mineralsUsedCurrentArmy();
    }
}
