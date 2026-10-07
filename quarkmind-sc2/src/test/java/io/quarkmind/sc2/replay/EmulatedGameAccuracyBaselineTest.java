package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
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

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class EmulatedGameAccuracyBaselineTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    private static final String[] ECONOMY_FIELDS = {
        "mineralsCurrent", "vespeneCurrent",
        "mineralsCollectionRate", "vespeneCollectionRate",
        "foodMade", "foodUsed", "workersActiveCount",
        "mineralsUsedCurrentArmy", "mineralsUsedCurrentEconomy",
        "mineralsUsedCurrentTechnology",
        "vespeneUsedCurrentArmy", "vespeneUsedCurrentEconomy",
        "vespeneUsedCurrentTechnology"
    };

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void emulatedGameAccuracyBaseline() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        Map<Integer, Map<UnitType, long[]>> unitAccum = new TreeMap<>();
        Map<Integer, Map<BuildingType, long[]>> bldgAccum = new TreeMap<>();
        Map<Integer, int[]> upgradeAccum = new TreeMap<>();
        Map<Integer, double[][]> economyAccum = new TreeMap<>();
        int totalPlayerRuns = 0;

        for (int cp : CHECKPOINTS_MIN) {
            unitAccum.put(cp, new EnumMap<>(UnitType.class));
            bldgAccum.put(cp, new EnumMap<>(BuildingType.class));
            upgradeAccum.put(cp, new int[]{0, 0});
            economyAccum.put(cp, new double[13][2]);
        }

        int processed = 0;
        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            Player[] players = replay.details.getPlayerList();
            if (players.length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    DivergenceReport report = ReplayValidationHarness.run(
                        replayPath, playerId, TICK_LIMIT);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= report.ticks().size()) continue;
                        DivergenceReport.TickSnapshot snap = report.ticks().get(tickIndex);

                        accumulateTypes(snap.groundTruthUnitsByType(),
                            snap.emulatedUnitsByType(), unitAccum.get(cp));

                        accumulateBuildingTypes(snap.groundTruthBuildingsByType(),
                            snap.emulatedBuildingsByType(), bldgAccum.get(cp));

                        int gtSize = snap.groundTruthUpgrades().size();
                        int matchCount = (int) snap.groundTruthUpgrades().stream()
                            .filter(snap.emulatedUpgrades()::contains).count();
                        upgradeAccum.get(cp)[0] += gtSize;
                        upgradeAccum.get(cp)[1] += matchCount;

                        accumulateEconomy(snap.groundTruthEconomy(),
                            snap.emulatedEconomy(), economyAccum.get(cp));
                    }
                    totalPlayerRuns++;
                } catch (Exception e) { /* skip failed replays */ }
            }
            processed++;
        }

        var sb = new StringBuilder();
        sb.append(String.format("# EmulatedGame Accuracy Baseline — %s%n%n", java.time.LocalDate.now()));
        sb.append(String.format("**Context:** Phase 2.5 (#379, child of #366)%n"));
        sb.append(String.format("**Dataset:** Oracle (118 replays, v4.9.3)%n"));
        sb.append(String.format("**Processed:** %d replays, %d player runs%n%n---%n%n", processed, totalPlayerRuns));

        for (int cp : CHECKPOINTS_MIN) {
            sb.append(String.format("## %d-minute checkpoint%n%n", cp));
            appendTypeAccuracy(sb, "Units", unitAccum.get(cp));
            appendBuildingAccuracy(sb, bldgAccum.get(cp));

            int[] upg = upgradeAccum.get(cp);
            double upgAcc = upg[0] > 0 ? 100.0 * upg[1] / upg[0] : 100.0;
            sb.append(String.format("### Upgrades%n%nAccuracy: %.1f%% (%d/%d)%n%n",
                upgAcc, upg[1], upg[0]));

            appendEconomyMape(sb, economyAccum.get(cp));
        }

        System.out.println(sb);
        Path reportPath = Path.of(System.getProperty("user.dir")).getParent()
            .resolve("docs/benchmarks/emulated-game-accuracy-baseline.md");
        Files.writeString(reportPath, sb.toString());

        assertThat(processed).as("Must process at least 100 replays").isGreaterThanOrEqualTo(100);
    }

    private static void accumulateTypes(Map<UnitType, Integer> gt, Map<UnitType, Integer> em,
                                        Map<UnitType, long[]> accum) {
        Set<UnitType> allTypes = EnumSet.noneOf(UnitType.class);
        allTypes.addAll(gt.keySet());
        allTypes.addAll(em.keySet());
        for (UnitType type : allTypes) {
            long[] counts = accum.computeIfAbsent(type, k -> new long[2]);
            counts[0] += gt.getOrDefault(type, 0);
            counts[1] += em.getOrDefault(type, 0);
        }
    }

    private static void accumulateBuildingTypes(Map<BuildingType, Integer> gt,
                                                Map<BuildingType, Integer> em,
                                                Map<BuildingType, long[]> accum) {
        Set<BuildingType> allTypes = EnumSet.noneOf(BuildingType.class);
        allTypes.addAll(gt.keySet());
        allTypes.addAll(em.keySet());
        for (BuildingType type : allTypes) {
            long[] counts = accum.computeIfAbsent(type, k -> new long[2]);
            counts[0] += gt.getOrDefault(type, 0);
            counts[1] += em.getOrDefault(type, 0);
        }
    }

    private static void accumulateEconomy(PlayerEconomyStats gt, PlayerEconomyStats em,
                                          double[][] accum) {
        float[] gtVec = gt.toFeatureVector();
        float[] emVec = em.toFeatureVector();
        for (int i = 0; i < 13; i++) {
            accum[i][0] += gtVec[i];
            accum[i][1] += emVec[i];
        }
    }

    private static void appendTypeAccuracy(StringBuilder sb, String category,
                                           Map<? extends Enum<?>, long[]> accum) {
        sb.append(String.format("### %s — Per-Type%n%n", category));
        sb.append(String.format("| %-25s | %8s | %8s | %8s |%n", "Type", "GT", "Emulated", "Accuracy"));
        sb.append(String.format("|%s|%s|%s|%s|%n", "-".repeat(27), "-".repeat(10), "-".repeat(10), "-".repeat(10)));
        long totalGt = 0, totalEm = 0;
        for (var entry : accum.entrySet()) {
            long gt = entry.getValue()[0];
            long em = entry.getValue()[1];
            double acc = gt > 0 ? 100.0 * Math.min(em, gt) / gt : (em == 0 ? 100.0 : 0.0);
            sb.append(String.format("| %-25s | %8d | %8d | %7.1f%% |%n", entry.getKey(), gt, em, acc));
            totalGt += gt;
            totalEm += em;
        }
        double totalAcc = totalGt > 0 ? 100.0 * Math.min(totalEm, totalGt) / totalGt : 100.0;
        sb.append(String.format("| %-25s | %8d | %8d | %7.1f%% |%n%n", "**TOTAL**", totalGt, totalEm, totalAcc));
    }

    private static void appendBuildingAccuracy(StringBuilder sb,
                                               Map<BuildingType, long[]> accum) {
        sb.append("### Buildings — Per-Type\n\n");
        sb.append(String.format("| %-25s | %8s | %8s | %8s |%n", "Type", "GT", "Emulated", "Accuracy"));
        sb.append(String.format("|%s|%s|%s|%s|%n", "-".repeat(27), "-".repeat(10), "-".repeat(10), "-".repeat(10)));
        long totalGt = 0, totalEm = 0;
        for (var entry : accum.entrySet()) {
            long gt = entry.getValue()[0];
            long em = entry.getValue()[1];
            double acc = gt > 0 ? 100.0 * Math.min(em, gt) / gt : (em == 0 ? 100.0 : 0.0);
            sb.append(String.format("| %-25s | %8d | %8d | %7.1f%% |%n", entry.getKey(), gt, em, acc));
            totalGt += gt;
            totalEm += em;
        }
        double totalAcc = totalGt > 0 ? 100.0 * Math.min(totalEm, totalGt) / totalGt : 100.0;
        sb.append(String.format("| %-25s | %8d | %8d | %7.1f%% |%n%n", "**TOTAL**", totalGt, totalEm, totalAcc));
    }

    private static void appendEconomyMape(StringBuilder sb, double[][] accum) {
        sb.append("### Economy — Aggregate Error\n\n");
        sb.append(String.format("| %-35s | %12s | %12s | %8s |%n", "Field", "GT (sum)", "Em (sum)", "Error%"));
        sb.append(String.format("|%s|%s|%s|%s|%n", "-".repeat(37), "-".repeat(14), "-".repeat(14), "-".repeat(10)));
        for (int i = 0; i < 13; i++) {
            double gt = accum[i][0] * 1000;
            double em = accum[i][1] * 1000;
            double error = gt != 0 ? 100.0 * Math.abs(em - gt) / Math.abs(gt) : 0.0;
            sb.append(String.format("| %-35s | %12.0f | %12.0f | %7.1f%% |%n",
                ECONOMY_FIELDS[i], gt, em, error));
        }
        sb.append("\n");
    }
}
