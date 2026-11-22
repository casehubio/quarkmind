package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
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
import java.util.DoubleSummaryStatistics;
import java.util.EnumSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Measures per-replay mineral patch geometry variance and quantifies the
 * mineral rate MAPE impact of using replay positions vs seeded positions.
 *
 * Refs #745
 */
@Tag("diagnostic")
class MineralPatchGeometryDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final float GAS_PER_WORKER_PER_MIN = 38.0f;
    private static final float MINERAL_PATCH_RADIUS = 1.0f;
    private static final float TOWN_HALL_RADIUS = 2.75f;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void measureGeometryImpact() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        DoubleSummaryStatistics closeC2C = new DoubleSummaryStatistics();
        DoubleSummaryStatistics farC2C = new DoubleSummaryStatistics();
        DoubleSummaryStatistics closeE2E = new DoubleSummaryStatistics();
        DoubleSummaryStatistics farE2E = new DoubleSummaryStatistics();
        List<Double> closeC2CList = new ArrayList<>();
        List<Double> farC2CList = new ArrayList<>();
        IntSummaryStatistics patchesPerBase = new IntSummaryStatistics();
        int totalBases = 0;

        Map<Integer, double[][]> replayEcon = new TreeMap<>();
        Map<Integer, double[][]> seededEcon = new TreeMap<>();
        for (int cp : CHECKPOINTS_MIN) {
            replayEcon.put(cp, new double[13][3]);
            seededEcon.put(cp, new double[13][3]);
        }
        int totalRuns = 0;

        for (int ri = 0; ri < replays.size(); ri++) {
            Path replayPath = replays.get(ri);
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            if ((ri + 1) % 20 == 0) {
                System.out.printf("  Progress: %d/%d replays%n", ri + 1, replays.size());
            }

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame gtDist = new ReplaySimulatedGame(replayPath, playerId);
                    gtDist.reset();
                    for (int t = 0; t < 5; t++) gtDist.tick();
                    List<Resource> patches = gtDist.getMineralPatches();
                    GameState snap = gtDist.snapshot();

                    for (var b : snap.myBuildings()) {
                        String typeName = b.type().name();
                        if (typeName.contains("NEXUS") || typeName.contains("COMMAND")
                            || typeName.contains("HATCHERY")) {
                            Point2d thPos = b.position();
                            List<Double> distances = new ArrayList<>();
                            for (Resource r : patches) {
                                double d = thPos.distanceTo(r.position());
                                if (d <= 12.0) distances.add(d);
                            }
                            if (distances.isEmpty()) continue;
                            distances.sort(Double::compare);
                            totalBases++;
                            patchesPerBase.accept(distances.size());

                            for (int i = 0; i < distances.size(); i++) {
                                double c2c = distances.get(i);
                                double e2e = c2c - MINERAL_PATCH_RADIUS - TOWN_HALL_RADIUS;
                                if (i < 4) {
                                    closeC2C.accept(c2c);
                                    closeE2E.accept(e2e);
                                    closeC2CList.add(c2c);
                                } else {
                                    farC2C.accept(c2c);
                                    farE2E.accept(e2e);
                                    farC2CList.add(c2c);
                                }
                            }
                        }
                    }

                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    ReplaySimulatedGame gt1 = new ReplaySimulatedGame(replayPath, playerId);
                    List<DivergenceReport.TickSnapshot> snap1 =
                        runWithMode(gt1, intents, TICK_LIMIT, race, true);

                    ReplaySimulatedGame gt2 = new ReplaySimulatedGame(replayPath, playerId);
                    List<DivergenceReport.TickSnapshot> snap2 =
                        runWithMode(gt2, intents, TICK_LIMIT, race, false);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex < snap1.size()
                            && snap1.get(tickIndex).groundTruthEconomy() != null
                            && snap1.get(tickIndex).emulatedEconomy() != null) {
                            accumulateEconomy(snap1.get(tickIndex).groundTruthEconomy(),
                                snap1.get(tickIndex).emulatedEconomy(), replayEcon.get(cp));
                        }
                        if (tickIndex < snap2.size()
                            && snap2.get(tickIndex).groundTruthEconomy() != null
                            && snap2.get(tickIndex).emulatedEconomy() != null) {
                            accumulateEconomy(snap2.get(tickIndex).groundTruthEconomy(),
                                snap2.get(tickIndex).emulatedEconomy(), seededEcon.get(cp));
                        }
                    }
                    totalRuns++;
                } catch (Exception e) { /* skip */ }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# Mineral Patch Geometry: Replay vs EM Seeded Distances — %s%n%n",
            java.time.LocalDate.now()));
        sb.append(String.format("**Dataset:** Oracle (%d replays), %d player runs, %d bases%n",
            replays.size(), totalRuns, totalBases));
        sb.append(String.format("**Seeded constants:** CLOSE_PATCH_DISTANCE=6.7, FAR_PATCH_DISTANCE=7.5%n%n"));
        sb.append("---\n\n");

        double closeStdDev = stdDev(closeC2CList);
        double farStdDev = stdDev(farC2CList);

        sb.append("## Center-to-Center Distances\n\n");
        sb.append("| Category | Min | Avg | Max | σ | Seeded |\n");
        sb.append("|---|---|---|---|---|---|\n");
        sb.append(String.format("| Close (first 4) | %.2f | %.2f | %.2f | %.2f | 6.70 |%n",
            closeC2C.getMin(), closeC2C.getAverage(), closeC2C.getMax(), closeStdDev));
        sb.append(String.format("| Far (remaining) | %.2f | %.2f | %.2f | %.2f | 7.50 |%n%n",
            farC2C.getMin(), farC2C.getAverage(), farC2C.getMax(), farStdDev));

        sb.append("## Edge-to-Edge Walk Distances\n\n");
        sb.append("| Category | Min | Avg | Max | Seeded |\n");
        sb.append("|---|---|---|---|---|\n");
        sb.append(String.format("| Close (first 4) | %.2f | %.2f | %.2f | %.2f |%n",
            closeE2E.getMin(), closeE2E.getAverage(), closeE2E.getMax(), 6.7 - 3.75));
        sb.append(String.format("| Far (remaining) | %.2f | %.2f | %.2f | %.2f |%n%n",
            farE2E.getMin(), farE2E.getAverage(), farE2E.getMax(), 7.5 - 3.75));

        sb.append(String.format("**Patches per base:** min=%d, avg=%.1f, max=%d%n%n",
            patchesPerBase.getMin(), patchesPerBase.getAverage(), patchesPerBase.getMax()));
        sb.append("---\n\n");

        String[] econFields = {"mineralsCurrent", "vespeneCurrent",
            "mineralsCollectionRate", "vespeneCollectionRate",
            "foodMade", "foodUsed", "workersActiveCount",
            "mineralsUsedCurrentArmy", "mineralsUsedCurrentEconomy",
            "mineralsUsedCurrentTechnology", "vespeneUsedCurrentArmy",
            "vespeneUsedCurrentEconomy", "vespeneUsedCurrentTechnology"};
        int[] keyFields = {2, 3, 0, 1, 6};

        sb.append("## Economy MAPE: Replay Positions vs Seeded Positions\n\n");
        for (int cp : CHECKPOINTS_MIN) {
            sb.append(String.format("### %d-minute checkpoint%n%n", cp));
            sb.append("| Field | Replay MAPE | Seeded MAPE | Delta |\n");
            sb.append("|---|---|---|---|\n");
            double[][] rep = replayEcon.get(cp);
            double[][] sed = seededEcon.get(cp);
            for (int i : keyFields) {
                double repMape = rep[i][0] != 0 ? rep[i][2] / Math.abs(rep[i][0]) * 100.0 : 0;
                double sedMape = sed[i][0] != 0 ? sed[i][2] / Math.abs(sed[i][0]) * 100.0 : 0;
                double delta = sedMape - repMape;
                sb.append(String.format("| %s | %.1f%% | %.1f%% | %+.1f pp |%n",
                    econFields[i], repMape, sedMape, delta));
            }
            sb.append("\n");
        }

        String report = sb.toString();
        System.out.println(report);

        Path outputPath = Path.of("../docs/benchmarks/mineral-patch-geometry.md");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, report);
        System.out.printf("Written to %s%n", outputPath);
    }

    private static List<DivergenceReport.TickSnapshot> runWithMode(
            ReplaySimulatedGame groundTruth, List<TimedIntent> intents,
            int tickLimit, Race race, boolean useReplayPositions) {

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
        groundTruth.reset();
        if (useReplayPositions) {
            emulated.setMineralPatchResources(groundTruth.getMineralPatches());
        }
        emulated.resetForHarness();

        int cursor = 0;
        List<DivergenceReport.TickSnapshot> snapshots =
            new ArrayList<>(Math.min(tickLimit, 10000));

        for (int tick = 0; tick < tickLimit && !groundTruth.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

            emulated.tick();
            groundTruth.tick();

            GameState gt = groundTruth.snapshot();

            emulated.processDeferredTrainIntents();
            emulated.processDeferredResearchIntents();

            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                emulated.applyIntent(intents.get(cursor));
                cursor++;
            }

            int gtGasRate = gt.playerEconomy().vespeneCollectionRate();
            if (gtGasRate > 0) {
                int estimatedGasWorkers = Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN));
                emulated.syncTotalGasWorkers(estimatedGasWorkers);
            }

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

    private static double stdDev(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double mean = values.stream().mapToDouble(d -> d).average().orElse(0);
        double sumSq = values.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum();
        return Math.sqrt(sumSq / (values.size() - 1));
    }
}
