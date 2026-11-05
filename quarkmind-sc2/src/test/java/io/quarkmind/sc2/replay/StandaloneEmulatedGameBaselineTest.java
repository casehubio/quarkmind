package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Measures EmulatedGame accuracy with ALL economy GT syncs disabled.
 * Buildings are still injected from GT (structural necessity — no build intents exist).
 * This establishes the honest starting point for standalone EmulatedGame accuracy.
 *
 * Refs #517
 */
@Tag("report")
class StandaloneEmulatedGameBaselineTest {

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
    void standaloneEmulatedGameBaseline() throws IOException {
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
            economyAccum.put(cp, new double[13][3]);
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

                    List<DivergenceReport.TickSnapshot> snapshots = runStandalone(
                        groundTruth, intents, TICK_LIMIT, race);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= snapshots.size()) continue;
                        var snap = snapshots.get(tickIndex);

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
        sb.append(String.format("# Standalone EmulatedGame Accuracy Baseline — %s%n%n",
            java.time.LocalDate.now()));
        sb.append(String.format("**Dataset:** Oracle (118 replays, v4.9.3)%n"));
        sb.append(String.format("**Processed:** %d replays, %d player runs%n",
            replays.size(), totalPlayerRuns));
        sb.append(String.format("**Method:** Tracker-event-driven inputs, ALL economy GT syncs disabled%n"));
        sb.append(String.format("**Buildings:** Injected from GT (structural — no build intents exist)%n%n"));
        sb.append("**Disabled GT syncs:** syncMineralWorkersFromGt, setMineralsForHarness, ");
        sb.append("setVespeneForHarness, syncGasRateFromGt, syncMineralRateFromGt, ");
        sb.append("syncVespeneRateFromGt, setSupplyCapForHarness, setSupplyUsedForHarness, ");
        sb.append("setWorkerCountForHarness, syncSpendingFromGt\n\n");
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

            sb.append("### Economy — Per-Field Error\n\n");
            sb.append("| Field | GT (sum) | EM (sum) | Abs Error (sum) | MAPE |\n");
            sb.append("|---|---|---|---|---|\n");
            String[] econFields = {"mineralsCurrent", "vespeneCurrent",
                "mineralsCollectionRate", "vespeneCollectionRate",
                "foodMade", "foodUsed", "workersActiveCount",
                "mineralsUsedCurrentArmy", "mineralsUsedCurrentEconomy",
                "mineralsUsedCurrentTechnology", "vespeneUsedCurrentArmy",
                "vespeneUsedCurrentEconomy", "vespeneUsedCurrentTechnology"};
            double[][] econ = economyAccum.get(cp);
            for (int i = 0; i < econFields.length && i < econ.length; i++) {
                double gt = econ[i][0];
                double em = econ[i][1];
                double absErr = econ[i][2];
                double mape = gt != 0 ? absErr / Math.abs(gt) * 100.0 : 0;
                sb.append(String.format("| %s | %.0f | %.0f | %.0f | %.1f%% |%n",
                    econFields[i], gt, em, absErr, mape));
            }
            sb.append("\n");
        }

        String report = sb.toString();
        System.out.println(report);

        Path outputPath = Path.of("../docs/benchmarks/standalone-emulated-game-baseline.md");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, report);

        System.out.printf("%n=== Standalone baseline written to %s ===%n", outputPath);
    }

    /**
     * Runs EmulatedGame with tracker-driven intents but NO economy GT syncs.
     * Buildings are injected from GT (structural necessity).
     */
    static List<DivergenceReport.TickSnapshot> runStandalone(
            ReplaySimulatedGame groundTruth, List<TimedIntent> intents,
            int tickLimit, Race race) {

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
        groundTruth.reset();
        emulated.setMineralPatchResources(groundTruth.getMineralPatches());
        emulated.resetForHarness();

        int cursor = 0;
        List<DivergenceReport.TickSnapshot> snapshots = new ArrayList<>(Math.min(tickLimit, 10000));

        for (int tick = 0; tick < tickLimit && !groundTruth.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

            // NO syncMineralWorkersFromGt — EmulatedGame tracks workers itself

            emulated.tick();
            groundTruth.tick();

            GameState gt = groundTruth.snapshot();

            // NO syncBuildings — EmulatedGame tracks buildings from tracker intents (#523)
            // NO setSupplyCapForHarness — EmulatedGame tracks supply from buildings
            // NO setVespeneForHarness (pre-tick) — EmulatedGame earns vespene via mining

            emulated.processDeferredTrainIntents();
            emulated.processDeferredResearchIntents();

            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                emulated.applyIntent(intents.get(cursor));
                cursor++;
            }

            // NO setMineralsForHarness (post-tick) — EmulatedGame uses own balance
            // NO setVespeneForHarness (post-tick) — EmulatedGame uses own balance
            // NO syncGasRateFromGt — EmulatedGame calculates own gas rate
            // NO syncMineralRateFromGt — EmulatedGame calculates own mineral rate
            // NO syncVespeneRateFromGt — EmulatedGame calculates own vespene rate
            // NO setSupplyUsedForHarness — EmulatedGame tracks own supply used
            // NO setWorkerCountForHarness — EmulatedGame counts own workers
            // NO syncSpendingFromGt — EmulatedGame tracks own spending

            GameState em = emulated.snapshot();

            Map<UnitType, Integer> gtUnitsByType = gt.myUnits().stream()
                .collect(Collectors.groupingBy(Unit::type, Collectors.summingInt(u -> 1)));
            Map<UnitType, Integer> emUnitsByType = em.myUnits().stream()
                .collect(Collectors.groupingBy(Unit::type, Collectors.summingInt(u -> 1)));
            Map<BuildingType, Integer> gtBldgsByType = gt.myBuildings().stream()
                .filter(Building::isComplete)
                .collect(Collectors.groupingBy(Building::type, Collectors.summingInt(b -> 1)));
            Map<BuildingType, Integer> emBldgsByType = em.myBuildings().stream()
                .filter(Building::isComplete)
                .collect(Collectors.groupingBy(Building::type, Collectors.summingInt(b -> 1)));

            snapshots.add(new DivergenceReport.TickSnapshot(
                tick,
                gt.myUnits().size(), em.myUnits().size(),
                gt.myBuildings().size(), em.myBuildings().size(),
                gt.minerals(), em.minerals(),
                gt.vespene(), em.vespene(),
                gtUnitsByType, emUnitsByType,
                gtBldgsByType, emBldgsByType,
                gt.playerUpgrades(), em.playerUpgrades(),
                gt.playerEconomy(), em.playerEconomy()));
        }

        return snapshots;
    }

    private static void accumulateEconomy(
            PlayerEconomyStats gt, PlayerEconomyStats em, double[][] accum) {
        accumulateField(accum, 0, gt.mineralsCurrent(), em.mineralsCurrent());
        accumulateField(accum, 1, gt.vespeneCurrent(), em.vespeneCurrent());
        accumulateField(accum, 2, gt.mineralsCollectionRate(), em.mineralsCollectionRate());
        accumulateField(accum, 3, gt.vespeneCollectionRate(), em.vespeneCollectionRate());
        accumulateField(accum, 4, gt.foodMade(), em.foodMade());
        accumulateField(accum, 5, gt.foodUsed(), em.foodUsed());
        accumulateField(accum, 6, gt.workersActiveCount(), em.workersActiveCount());
        accumulateField(accum, 7, gt.mineralsUsedCurrentArmy(), em.mineralsUsedCurrentArmy());
        accumulateField(accum, 8, gt.mineralsUsedCurrentEconomy(), em.mineralsUsedCurrentEconomy());
        accumulateField(accum, 9, gt.mineralsUsedCurrentTechnology(), em.mineralsUsedCurrentTechnology());
        accumulateField(accum, 10, gt.vespeneUsedCurrentArmy(), em.vespeneUsedCurrentArmy());
        accumulateField(accum, 11, gt.vespeneUsedCurrentEconomy(), em.vespeneUsedCurrentEconomy());
        accumulateField(accum, 12, gt.vespeneUsedCurrentTechnology(), em.vespeneUsedCurrentTechnology());
    }

    private static void accumulateField(double[][] accum, int index, double gtVal, double emVal) {
        accum[index][0] += gtVal;
        accum[index][1] += emVal;
        accum[index][2] += Math.abs(gtVal - emVal);
    }
}
