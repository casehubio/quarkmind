package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag("diagnostic")
class ContentionCalibrationDiagnosticTest {

    private static final Point2d BASE_POS = new Point2d(50, 50);
    private static final String BASE_TAG = "base-1";

    @Test
    void measureEmContentionAndRerouting() {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {tags.add("w-" + i);}
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        Map<String, MineralPatch> prevPatch        = new HashMap<>();
        Map<String, WorkerTask>   prevTask         = new HashMap<>();
        int                       rerouteCount     = 0;
        List<Integer>             cycleLengths     = new ArrayList<>();
        Map<String, Integer>      lastDeliveryTick = new HashMap<>();
        double                    totalMinerals    = 0;

        for (int tick = 0; tick < 304; tick++) {
            TickIncome income = sim.tick(tick * (double) SC2Data.LOOPS_PER_TICK);
            totalMinerals += income.mineralIncome();

            for (SimulatedWorker w : sim.allWorkers()) {
                if (w.assignedGasSlot != null) {continue;}

                MineralPatch prev = prevPatch.get(w.tag);
                if (w.assignedPatch != null && prev != null && w.assignedPatch != prev) {
                    rerouteCount++;
                }
                prevPatch.put(w.tag, w.assignedPatch);
            }

            for (SimulatedWorker w : sim.allWorkers()) {
                if (w.assignedGasSlot != null) {continue;}
                WorkerTask prev = prevTask.get(w.tag);
                if (prev == WorkerTask.MINING_MINERALS && w.task == WorkerTask.RETURNING_MINERALS) {
                    Integer lastTick = lastDeliveryTick.get(w.tag);
                    if (lastTick != null && tick - lastTick > 0) {
                        cycleLengths.add(tick - lastTick);
                    }
                    lastDeliveryTick.put(w.tag, tick);
                }
                prevTask.put(w.tag, w.task);
            }
        }

        double meanCycle = cycleLengths.stream().mapToInt(i -> i).average().orElse(0);
        double variance  = cycleLengths.stream().mapToDouble(i -> (i - meanCycle) * (i - meanCycle)).average().orElse(0);
        double sigma     = Math.sqrt(variance);

        int[] distribution = new int[20];
        for (int c : cycleLengths) {
            if (c < distribution.length) {distribution[c]++;}
        }

        System.out.println("=== EM Contention Calibration (12w, 304 ticks) ===");
        System.out.println("Reroute events: " + rerouteCount);
        System.out.printf("Total minerals: %.0f%n", totalMinerals);
        System.out.printf("Cycle mean: %.2f ticks, sigma: %.2f (cycles tracked: %d)%n", meanCycle, sigma, cycleLengths.size());
        System.out.println("Cycle distribution:");
        for (int i = 0; i < distribution.length; i++) {
            if (distribution[i] > 0) {
                System.out.printf("  %d-tick: %d (%.1f%%)%n", i, distribution[i],
                                  100.0 * distribution[i] / cycleLengths.size());
            }
        }
    }

    @Test
    void cadenceMismatchWithConditionalApproach() {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags = new ArrayList<>();
        for (int i = 1; i <= 12; i++) { tags.add("w-" + i); }
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        List<Double> deliveries = new ArrayList<>();
        double totalMinerals = 0;
        for (int tick = 0; tick < 304; tick++) {
            TickIncome income = sim.tick(tick * (double) SC2Data.LOOPS_PER_TICK);
            deliveries.add(income.mineralIncome());
            totalMinerals += income.mineralIncome();
        }

        long nonZeroTicks = deliveries.stream().filter(d -> d > 0).count();
        long deliveriesMultOf5 = deliveries.stream()
            .filter(d -> d > 0)
            .filter(d -> d % 5 == 0)
            .count();

        System.out.println("=== Cadence Report (12w, 304 ticks) ===");
        System.out.printf("Total minerals: %.0f%n", totalMinerals);
        System.out.println("Non-zero delivery ticks: " + nonZeroTicks + " / 304");
        System.out.printf("Delivery rate: %.1f%%%n", 100.0 * nonZeroTicks / 304);
        System.out.printf("Deliveries that are multiples of 5: %d / %d%n", deliveriesMultOf5, nonZeroTicks);
    }

    @Test
    void sweepWaitAbsorptionThreshold() throws Exception {
        double[] thresholds = {0, 2, 5, 8, 10, 12, 15, 18, 20, 25, Double.MAX_VALUE};
        System.out.println("=== Wait Absorption Threshold Sweep (pinned single-base) ===");
        System.out.printf("%-12s | %-18s | %-18s%n",
                          "Threshold", "tick 150 (24w)", "tick 304 (36w)");
        System.out.println("-".repeat(55));

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (double threshold : thresholds) {
            WorkerSimulation.WAIT_ABSORPTION_THRESHOLD = threshold;
            try {
                int[]  result = runPinnedCalibration(mapper);
                double pct304 = result[1] > 0 ? (result[0] - result[1]) * 100.0 / result[1] : 0;
                double pct150 = result[3] > 0 ? (result[2] - result[3]) * 100.0 / result[3] : 0;

                String threshStr = threshold == Double.MAX_VALUE ? "disabled" : String.format("%.0f", threshold);
                System.out.printf("%-12s | %+6.1f%% (EM=%4d) | %+6.1f%% (EM=%4d)%n",
                                  threshStr, pct150, result[2], pct304, result[0]);
            } finally {
                WorkerSimulation.WAIT_ABSORPTION_THRESHOLD = Double.MAX_VALUE;
            }
        }
    }

    @Test
    void sweepDodgeScaleAtHighSaturation() throws Exception {
        float[] dodgeScales = {0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.40f, 0.50f, 0.75f, 1.0f};
        System.out.println("=== High-Saturation Dodge Scale Sweep (pinned single-base) ===");
        System.out.println("LOW_SATURATION_DODGE_SCALE stays at 0.25 (<=2 w/p)");
        System.out.println("Swept value applies to >2 w/p (currently 1.0)");
        System.out.printf("%-12s | %-18s | %-18s%n",
                          "Dodge >2w/p", "tick 150 (24w)", "tick 304 (36w)");
        System.out.println("-".repeat(55));

        com.fasterxml.jackson.databind.ObjectMapper mapper      = new com.fasterxml.jackson.databind.ObjectMapper();
        float                                       savedLowSat = WorkerSimulation.LOW_SATURATION_DODGE_SCALE;

        for (float scale : dodgeScales) {
            WorkerSimulation.HIGH_SATURATION_DODGE_SCALE = scale;
            try {
                int[]  result = runPinnedCalibration(mapper);
                double pct304 = pctDelta(new int[]{result[0], result[1]});
                double pct150 = pctDelta(new int[]{result[2], result[3]});
                System.out.printf("%-12.2f | %+6.1f%% (EM=%4d) | %+6.1f%% (EM=%4d)%n",
                                  scale, pct150, result[2], pct304, result[0]);
            } finally {
                WorkerSimulation.HIGH_SATURATION_DODGE_SCALE = 1.0f;
                WorkerSimulation.LOW_SATURATION_DODGE_SCALE  = savedLowSat;
            }
        }
    }


    private static double pctDelta(int[] result) {
        return result[1] > 0 ? (result[0] - result[1]) * 100.0 / result[1] : 0;
    }


    private int[] runPinnedCalibration(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-single-base-probes.json")) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame                                  game     = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                      .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                                                 new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                                                      .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            if (baseline.geyserPositions() != null) {
                io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                game.setGeyserResources(baseline.geyserPositions().stream()
                                                .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                                                                                           new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                                                .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                                                .toList());
            }

            List<io.quarkmind.sc2.playbook.CalibrationSnapshot> sc2                  = baseline.snapshots();
            int                                                 tickLimit            = sc2.size();
            int                                                 sc2ProbesInjected    = 0;
            int                                                 sc2StartProbes       = sc2.get(0).units().getOrDefault("PROBE", 0);
            int                                                 sc2FinalProbes       = sc2.get(tickLimit - 1).units().getOrDefault("PROBE", 0);
            int                                                 sc2TotalToInject     = sc2FinalProbes - sc2StartProbes;
            int                                                 sc2ProbeHighWater    = sc2StartProbes;
            java.util.Map<String, Integer>                      sc2BuildingHighWater = new java.util.HashMap<>(sc2.get(0).buildings());

            int emAt150 = 0, sc2At150 = 0;

            for (int tick = 0; tick < tickLimit; tick++) {
                io.quarkmind.sc2.playbook.CalibrationSnapshot expected = sc2.get(tick);
                io.quarkmind.sc2.playbook.CalibrationSnapshot actual   = io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tick, game.snapshot());

                if (tick == 150) {
                    emAt150  = actual.minerals();
                    sc2At150 = expected.minerals();
                }

                game.tick();
                game.processDeferredResearchIntents();

                if (tick + 1 < tickLimit) {
                    io.quarkmind.sc2.playbook.CalibrationSnapshot next       = sc2.get(tick + 1);
                    int                                           nextProbes = next.units().getOrDefault("PROBE", 0);
                    if (nextProbes > sc2ProbeHighWater && sc2ProbesInjected < sc2TotalToInject) {
                        int toInject = Math.min(nextProbes - sc2ProbeHighWater, sc2TotalToInject - sc2ProbesInjected);
                        for (int p = 0; p < toInject; p++) {
                            game.injectWorkerForPinnedCalibration();
                        }
                        sc2ProbesInjected += toInject;
                        sc2ProbeHighWater = nextProbes;
                    }
                    for (var entry : next.buildings().entrySet()) {
                        int cur = sc2BuildingHighWater.getOrDefault(entry.getKey(), 0);
                        if (entry.getValue() > cur) {
                            io.quarkmind.domain.BuildingType bt = io.quarkmind.domain.BuildingType.valueOf(entry.getKey());
                            sc2BuildingHighWater.put(entry.getKey(), entry.getValue());
                            for (int b = 0; b < entry.getValue() - cur; b++) {
                                game.applyIntent(new io.quarkmind.sc2.intent.TimedIntent(
                                        (long) (tick + 1) * io.quarkmind.domain.SC2Data.LOOPS_PER_TICK,
                                        io.quarkmind.sc2.intent.BuildIntent.of("cal-pinned", bt, loadBuildingPosition(bt, cur + b, baseline.basePosition()))));
                            }
                        }
                    }
                }
            }

            io.quarkmind.sc2.playbook.CalibrationSnapshot finalSc2 = sc2.get(tickLimit - 1);
            io.quarkmind.sc2.playbook.CalibrationSnapshot finalEm  = io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tickLimit - 1, game.snapshot());
            return new int[]{finalEm.minerals(), finalSc2.minerals(), emAt150, sc2At150};
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<float[]> loadBuildingPositionsList(String type) {
        try (java.io.InputStream is = ContentionCalibrationDiagnosticTest.class.getClassLoader()
                .getResourceAsStream("calibration/cal-single-base-probes-buildings.json")) {
            if (is == null) return java.util.Collections.emptyList();
            java.util.Map<String, java.util.List<Number>> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(is, java.util.Map.class);
            java.util.List<float[]> result = new java.util.ArrayList<>();
            for (var e : raw.entrySet()) {
                if (e.getKey().startsWith(type + "-")) {
                    result.add(new float[]{e.getValue().get(0).floatValue(), e.getValue().get(1).floatValue()});
                }
            }
            return result;
        } catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    private io.quarkmind.domain.Point2d loadBuildingPosition(io.quarkmind.domain.BuildingType bt, int index, float[] basePos) {
        java.util.List<float[]> positions = loadBuildingPositionsList(bt.name());
        if (index < positions.size()) {
            float[] pos = positions.get(index);
            return new io.quarkmind.domain.Point2d(pos[0], pos[1]);
        }
        return new io.quarkmind.domain.Point2d(basePos[0] + 5, basePos[1] + 5);
    }

    private int[] runBaseline(com.fasterxml.jackson.databind.ObjectMapper mapper, String baselinePath) throws Exception {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(baselinePath)) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame                                  game     = new EmulatedGame();
            io.quarkmind.domain.Race                      race     = io.quarkmind.domain.Race.valueOf(baseline.race());
            game.setPlayerRaceModel(RaceModelFactory.forRace(race));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                      .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                                                 new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                                                      .toList());
            }
            game.reset();
            if (baseline.geyserPositions() != null) {
                io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                game.setGeyserResources(baseline.geyserPositions().stream()
                                                .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                                                                                           new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                                                .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                                                .toList());
            }

            int tickLimit = Math.min(304, baseline.snapshots().size());
            for (int tick = 0; tick < tickLimit; tick++) {
                game.tick();
            }
            io.quarkmind.sc2.playbook.CalibrationSnapshot finalEm  = io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tickLimit - 1, game.snapshot());
            io.quarkmind.sc2.playbook.CalibrationSnapshot finalSc2 = baseline.snapshots().get(tickLimit - 1);
            return new int[]{finalEm.minerals(), finalSc2.minerals()};
        }
    }

    @Test
    void sweepCombinedAbsorptionAndDodge() throws Exception {
        double[] thresholds = {10, 15, 20};
        float[] dodgeScales = {0.25f, 0.30f, 0.40f, 0.50f, 1.0f};
        System.out.println("=== Combined Sweep: absorption threshold x dodge scale ===");
        System.out.printf("%-10s | %-10s | %-18s | %-18s%n", "Threshold", "Dodge>2", "tick 150 (24w)", "tick 304 (36w)");
        System.out.println("-".repeat(65));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (double threshold : thresholds) {
            for (float scale : dodgeScales) {
                WorkerSimulation.WAIT_ABSORPTION_THRESHOLD = threshold;
                WorkerSimulation.HIGH_SATURATION_DODGE_SCALE = scale;
                try {
                    int[] result = runPinnedCalibration(mapper);
                    double pct304 = pctDelta(new int[]{result[0], result[1]});
                    double pct150 = pctDelta(new int[]{result[2], result[3]});
                    System.out.printf("%-10.0f | %-10.2f | %+6.1f%% (EM=%4d) | %+6.1f%% (EM=%4d)%n",
                        threshold, scale, pct150, result[2], pct304, result[0]);
                } finally {
                    WorkerSimulation.WAIT_ABSORPTION_THRESHOLD = Double.MAX_VALUE;
                    WorkerSimulation.HIGH_SATURATION_DODGE_SCALE = 1.0f;
                }
            }
            System.out.println("-".repeat(65));
        }
        System.out.println("Current (disabled/1.0): tick150 +6.5%, tick304 -6.2%");
    }

    @Test
    void sweepApproachAnimationWithAbsorption() throws Exception {
        double[] approaches = {20, 22, 24, 26, 28};
        double[] thresholds = {Double.MAX_VALUE, 15, 10};
        System.out.println("=== Approach Animation Sweep with Absorption (pinned single-base) ===");
        System.out.printf("%-10s | %-10s | %-18s | %-18s%n", "Approach", "Threshold", "tick 150 (24w)", "tick 304 (36w)");
        System.out.println("-".repeat(65));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        double savedApproach = SimulatedWorker.APPROACH_ANIMATION_LOOPS;
        for (double approach : approaches) {
            for (double threshold : thresholds) {
                SimulatedWorker.APPROACH_ANIMATION_LOOPS = approach;
                WorkerSimulation.WAIT_ABSORPTION_THRESHOLD = threshold;
                try {
                    int[] result = runPinnedCalibration(mapper);
                    double pct304 = pctDelta(new int[]{result[0], result[1]});
                    double pct150 = pctDelta(new int[]{result[2], result[3]});
                    String threshStr = threshold == Double.MAX_VALUE ? "off" : String.format("%.0f", threshold);
                    System.out.printf("%-10.0f | %-10s | %+6.1f%% (EM=%4d) | %+6.1f%% (EM=%4d)%n",
                        approach, threshStr, pct150, result[2], pct304, result[0]);
                } finally {
                    SimulatedWorker.APPROACH_ANIMATION_LOOPS = savedApproach;
                    WorkerSimulation.WAIT_ABSORPTION_THRESHOLD = Double.MAX_VALUE;
                }
            }
            System.out.println("-".repeat(65));
        }
    }

    @Test
    void sweepMinApproachAfterWait() throws Exception {
        double[] minApproaches = {0, 2, 4, 5, 6, 8, 10, 12, 15, 20};
        System.out.println("=== Min Approach After Wait Sweep (pinned single-base, TorchesAIE) ===");
        System.out.println("Formula: approach = max(minApproach, APPROACH_ANIMATION(20) - waited)");
        System.out.printf("%-12s | %-18s | %-18s%n", "MinApproach", "tick 150 (24w)", "tick 304 (36w)");
        System.out.println("-".repeat(55));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        double saved = WorkerSimulation.MIN_APPROACH_AFTER_WAIT;
        for (double minAppr : minApproaches) {
            WorkerSimulation.MIN_APPROACH_AFTER_WAIT = minAppr;
            try {
                int[] result = runPinnedCalibration(mapper);
                double pct304 = pctDelta(new int[]{result[0], result[1]});
                double pct150 = pctDelta(new int[]{result[2], result[3]});
                System.out.printf("%-12.0f | %+6.1f%% (EM=%4d) | %+6.1f%% (EM=%4d)%n",
                    minAppr, pct150, result[2], pct304, result[0]);
            } finally {
                WorkerSimulation.MIN_APPROACH_AFTER_WAIT = saved;
            }
        }
        System.out.println("20 = disabled (floor equals base → no absorption)");
    }

    @Test
    void measureEmVsSc2PerWorkerRate() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        int[] workerCounts = {12, 16, 24, 36};
        System.out.println("=== EM vs SC2 Per-Worker Income Rate on TorchesAIE ===");
        System.out.printf("%-8s | %-8s | %-14s | %-14s | %-10s%n",
            "Workers", "w/patch", "EM min/tick", "Per-worker", "vs 8w");
        System.out.println("-".repeat(65));

        double baseline8wRate = 0;
        for (int targetW : workerCounts) {
            try (java.io.InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("calibration/cal-single-base-probes.json")) {
                io.quarkmind.sc2.playbook.CalibrationBaseline bl =
                    mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
                EmulatedGame game = new EmulatedGame();
                game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
                var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
                if (cachedTerrain.isPresent()) {
                    game.setTerrainGrid(cachedTerrain.get());
                    game.setInitialBasePosition(new io.quarkmind.domain.Point2d(bl.basePosition()[0], bl.basePosition()[1]));
                }
                if (bl.mineralPatches() != null) {
                    game.setMineralPatchResources(bl.mineralPatches().stream()
                        .map(p -> new io.quarkmind.domain.Resource("cal-" + bl.mineralPatches().indexOf(p),
                            new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                        .toList());
                }
                game.setAutoTransferEnabled(false);
                game.reset();
                for (int i = 0; i < targetW - 12; i++) {
                    game.injectWorkerForPinnedCalibration();
                }
                int warmup = 100;
                for (int t = 0; t < warmup; t++) game.tick();
                int startMinerals = game.snapshot().minerals();
                int measure = 100;
                for (int t = 0; t < measure; t++) game.tick();
                int endMinerals = game.snapshot().minerals();
                double income = endMinerals - startMinerals;
                double ratePerTick = income / (double) measure;
                double perWorker = ratePerTick / targetW;
                if (targetW == workerCounts[0]) baseline8wRate = perWorker;
                double vsBaseline = baseline8wRate > 0 ? perWorker / baseline8wRate * 100 : 100;
                System.out.printf("%-8d | %-8.1f | %-14.1f | %-14.3f | %-10.1f%%%n",
                    targetW, targetW / 8.0, ratePerTick, perWorker, vsBaseline);
            }
        }
        System.out.println();
        System.out.println("--- Collision DISABLED ---");
        WorkerSimulation.COLLISION_ENABLED = false;
        baseline8wRate = 0;
        for (int targetW : workerCounts) {
            try (java.io.InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("calibration/cal-single-base-probes.json")) {
                io.quarkmind.sc2.playbook.CalibrationBaseline bl =
                    mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
                EmulatedGame game = new EmulatedGame();
                game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
                var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
                if (cachedTerrain.isPresent()) {
                    game.setTerrainGrid(cachedTerrain.get());
                    game.setInitialBasePosition(new io.quarkmind.domain.Point2d(bl.basePosition()[0], bl.basePosition()[1]));
                }
                if (bl.mineralPatches() != null) {
                    game.setMineralPatchResources(bl.mineralPatches().stream()
                        .map(p -> new io.quarkmind.domain.Resource("cal-" + bl.mineralPatches().indexOf(p),
                            new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                        .toList());
                }
                game.setAutoTransferEnabled(false);
                game.reset();
                for (int i = 0; i < Math.max(0, targetW - 12); i++) {
                    game.injectWorkerForPinnedCalibration();
                }
                int warmup = 100;
                for (int t = 0; t < warmup; t++) game.tick();
                int startMinerals = game.snapshot().minerals();
                int measure = 100;
                for (int t = 0; t < measure; t++) game.tick();
                int endMinerals = game.snapshot().minerals();
                double income = endMinerals - startMinerals;
                double ratePerTick = income / (double) measure;
                double perWorker = ratePerTick / targetW;
                if (targetW == workerCounts[0]) baseline8wRate = perWorker;
                double vsBaseline = baseline8wRate > 0 ? perWorker / baseline8wRate * 100 : 100;
                System.out.printf("%-8d | %-8.1f | %-14.1f | %-14.3f | %-10.1f%%%n",
                    targetW, targetW / 8.0, ratePerTick, perWorker, vsBaseline);
            }
        }
        WorkerSimulation.COLLISION_ENABLED = true;
        System.out.println();
        System.out.println("SC2 reference (dual-run baselines, TorchesAIE):");
        System.out.println("  8w:  0.96/worker/tick (100%)");
        System.out.println("  12w: 0.95/worker/tick (99%)");
        System.out.println("  16w: 0.88/worker/tick (92%)");
    }

    @Test
    void measureEmWalkExtensionBySaturation() {
        int[] workerCounts = {12, 16, 24, 36};
        System.out.println("=== EM Walk Extension by Saturation (100 ticks, flat grid) ===");
        System.out.printf("%-8s | %-8s | %-12s | %-12s | %-12s%n",
            "Workers", "w/patch", "Cycles", "Total cycle", "Income/tick");
        System.out.println("-".repeat(65));

        for (int wc : workerCounts) {
            WorkerSimulation sim = new WorkerSimulation();
            sim.onTownHallCompleted(BASE_TAG, BASE_POS);
            List<String> tags = new java.util.ArrayList<>();
            for (int i = 1; i <= wc; i++) tags.add("w-" + i);
            sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

            List<Integer> cycleLengths = new java.util.ArrayList<>();
            java.util.Map<String, Integer> lastDeliveryTick = new java.util.HashMap<>();
            double totalMinerals = 0;

            for (int tick = 0; tick < 100; tick++) {
                TickIncome income = sim.tick(tick * (double) io.quarkmind.domain.SC2Data.LOOPS_PER_TICK);
                totalMinerals += income.mineralIncome();
                for (SimulatedWorker w : sim.allWorkers()) {
                    if (w.assignedGasSlot != null) continue;
                    WorkerTask prev = lastDeliveryTick.containsKey(w.tag) ? WorkerTask.RETURNING_MINERALS : null;
                    if (w.task == WorkerTask.RETURNING_MINERALS && w.cargo > 0) {
                        Integer lastTick = lastDeliveryTick.get(w.tag);
                        if (lastTick != null && tick - lastTick > 0) {
                            cycleLengths.add(tick - lastTick);
                        }
                        lastDeliveryTick.put(w.tag, tick);
                    }
                }
            }

            double meanCycle = cycleLengths.isEmpty() ? 0 :
                cycleLengths.stream().mapToInt(i -> i).average().orElse(0);
            System.out.printf("%-8d | %-8.1f | %-12d | %-12.1f | %-12.1f%n",
                wc, wc / 8.0, cycleLengths.size(), meanCycle, totalMinerals / 100);
        }
    }

    @Test
    void verifyRallyExitPosition() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-single-base-probes.json")) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame                                  game     = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                      .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                                                 new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                                                      .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            if (baseline.geyserPositions() != null) {
                io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                game.setGeyserResources(baseline.geyserPositions().stream()
                                                .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                                                                                           new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                                                .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                                                .toList());
            }

            java.util.List<io.quarkmind.sc2.playbook.CalibrationSnapshot> sc2                  = baseline.snapshots();
            int                                                           tickLimit            = sc2.size();
            int                                                           sc2ProbesInjected    = 0;
            int                                                           sc2StartProbes       = sc2.get(0).units().getOrDefault("PROBE", 0);
            int                                                           sc2FinalProbes       = sc2.get(tickLimit - 1).units().getOrDefault("PROBE", 0);
            int                                                           sc2TotalToInject     = sc2FinalProbes - sc2StartProbes;
            int                                                           sc2ProbeHighWater    = sc2StartProbes;
            java.util.Map<String, Integer>                                sc2BuildingHighWater = new java.util.HashMap<>(sc2.get(0).buildings());

            System.out.println("=== Per-Tick Economy Diagnostic (pinned single-base) ===");
            System.out.printf("%-6s | %-6s | %-6s | %-6s | %-7s | %-6s%n",
                              "Tick", "SC2min", "EMmin", "Delta", "Probes", "Injected");
            System.out.println("-".repeat(55));

            int totalInjected = 0;
            for (int tick = 0; tick < tickLimit; tick++) {
                io.quarkmind.sc2.playbook.CalibrationSnapshot expected = sc2.get(tick);
                io.quarkmind.sc2.playbook.CalibrationSnapshot actual   = io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tick, game.snapshot());

                int sc2Min = expected.minerals();
                int emMin  = actual.minerals();
                int delta  = emMin - sc2Min;
                int probes = expected.units().getOrDefault("PROBE", 0);

                if (tick % 10 == 0 || tick == tickLimit - 1 || (tick > 0 && Math.abs(delta) > Math.abs(emMin - sc2.get(tick - 1).minerals()) + 20)) {
                    System.out.printf("%-6d | %-6d | %-6d | %+6d | %-7d | %-6d%n",
                                      tick, sc2Min, emMin, delta, probes, totalInjected);
                }

                game.tick();
                game.processDeferredResearchIntents();

                if (tick + 1 < tickLimit) {
                    io.quarkmind.sc2.playbook.CalibrationSnapshot next       = sc2.get(tick + 1);
                    int                                           nextProbes = next.units().getOrDefault("PROBE", 0);
                    if (nextProbes > sc2ProbeHighWater && sc2ProbesInjected < sc2TotalToInject) {
                        int toInject = Math.min(nextProbes - sc2ProbeHighWater, sc2TotalToInject - sc2ProbesInjected);
                        for (int p = 0; p < toInject; p++) {
                            game.injectWorkerForPinnedCalibration();
                            totalInjected++;
                        }
                        sc2ProbesInjected += toInject;
                        sc2ProbeHighWater = nextProbes;
                    }
                    for (var entry : next.buildings().entrySet()) {
                        int cur = sc2BuildingHighWater.getOrDefault(entry.getKey(), 0);
                        if (entry.getValue() > cur) {
                            io.quarkmind.domain.BuildingType bt = io.quarkmind.domain.BuildingType.valueOf(entry.getKey());
                            sc2BuildingHighWater.put(entry.getKey(), entry.getValue());
                            for (int b = 0; b < entry.getValue() - cur; b++) {
                                game.applyIntent(new io.quarkmind.sc2.intent.TimedIntent(
                                        (long) (tick + 1) * io.quarkmind.domain.SC2Data.LOOPS_PER_TICK,
                                        io.quarkmind.sc2.intent.BuildIntent.of("cal-pinned", bt, loadBuildingPosition(bt, cur + b, baseline.basePosition()))));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void pinnedNoBuildingsComparison() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        System.out.println("=== Pinned Single-Base: No Buildings vs With Buildings ===");

        for (boolean skipBuildings : new boolean[]{false, true}) {
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-single-base-probes.json")) {
                io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
                EmulatedGame                                  game     = new EmulatedGame();
                game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
                var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
                if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                    game.setTerrainGrid(cachedTerrain.get());
                    game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
                }
                if (baseline.mineralPatches() != null) {
                    game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                          .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                                                     new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                                                          .toList());
                }
                game.setAutoTransferEnabled(false);
                game.reset();
                if (baseline.geyserPositions() != null) {
                    io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                    game.setGeyserResources(baseline.geyserPositions().stream()
                                                    .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                                                                                               new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                                                    .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                                                    .toList());
                }

                java.util.List<io.quarkmind.sc2.playbook.CalibrationSnapshot> sc2                  = baseline.snapshots();
                int                                                           tickLimit            = sc2.size();
                int                                                           sc2ProbesInjected    = 0;
                int                                                           sc2StartProbes       = sc2.get(0).units().getOrDefault("PROBE", 0);
                int                                                           sc2FinalProbes       = sc2.get(tickLimit - 1).units().getOrDefault("PROBE", 0);
                int                                                           sc2TotalToInject     = sc2FinalProbes - sc2StartProbes;
                int                                                           sc2ProbeHighWater    = sc2StartProbes;
                java.util.Map<String, Integer>                                sc2BuildingHighWater = new java.util.HashMap<>(sc2.get(0).buildings());
                int                                                           buildingCostSkipped  = 0;

                for (int tick = 0; tick < tickLimit; tick++) {
                    game.tick();
                    game.processDeferredResearchIntents();
                    if (tick + 1 < tickLimit) {
                        io.quarkmind.sc2.playbook.CalibrationSnapshot next       = sc2.get(tick + 1);
                        int                                           nextProbes = next.units().getOrDefault("PROBE", 0);
                        if (nextProbes > sc2ProbeHighWater && sc2ProbesInjected < sc2TotalToInject) {
                            int toInject = Math.min(nextProbes - sc2ProbeHighWater, sc2TotalToInject - sc2ProbesInjected);
                            for (int p = 0; p < toInject; p++) {
                                game.injectWorkerForPinnedCalibration();
                            }
                            sc2ProbesInjected += toInject;
                            sc2ProbeHighWater = nextProbes;
                        }
                        if (!skipBuildings) {
                            for (var entry : next.buildings().entrySet()) {
                                int cur = sc2BuildingHighWater.getOrDefault(entry.getKey(), 0);
                                if (entry.getValue() > cur) {
                                    io.quarkmind.domain.BuildingType bt = io.quarkmind.domain.BuildingType.valueOf(entry.getKey());
                                    sc2BuildingHighWater.put(entry.getKey(), entry.getValue());
                                    for (int b = 0; b < entry.getValue() - cur; b++) {
                                        game.applyIntent(new io.quarkmind.sc2.intent.TimedIntent(
                                                (long) (tick + 1) * io.quarkmind.domain.SC2Data.LOOPS_PER_TICK,
                                                io.quarkmind.sc2.intent.BuildIntent.of("cal-pinned", bt, loadBuildingPosition(bt, cur + b, baseline.basePosition()))));
                                        buildingCostSkipped += io.quarkmind.domain.SC2Data.mineralCost(bt);
                                    }
                                }
                            }
                        } else {
                            for (var entry : next.buildings().entrySet()) {
                                int cur = sc2BuildingHighWater.getOrDefault(entry.getKey(), 0);
                                if (entry.getValue() > cur) {
                                    io.quarkmind.domain.BuildingType bt = io.quarkmind.domain.BuildingType.valueOf(entry.getKey());
                                    buildingCostSkipped += (entry.getValue() - cur) * io.quarkmind.domain.SC2Data.mineralCost(bt);
                                    sc2BuildingHighWater.put(entry.getKey(), entry.getValue());
                                }
                            }
                        }
                    }
                }
                io.quarkmind.sc2.playbook.CalibrationSnapshot finalEm = io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tickLimit - 1, game.snapshot());
                int                                           sc2Min  = sc2.get(tickLimit - 1).minerals();
                int                                           emMin   = finalEm.minerals();
                String                                        mode    = skipBuildings ? "NO buildings" : "WITH buildings";
                double                                        pct     = sc2Min > 0 ? (emMin - sc2Min) * 100.0 / sc2Min : 0;
                int                                           sc2Adj  = sc2Min + (skipBuildings ? buildingCostSkipped : 0);
                double                                        pctAdj  = sc2Adj > 0 ? (emMin - sc2Adj) * 100.0 / sc2Adj : 0;
                System.out.printf("%s: EM=%d SC2=%d delta=%+d (%.1f%%) | SC2+bldgCosts=%d adj-delta=%+d (%.1f%%)%n",
                                  mode, emMin, sc2Min, emMin - sc2Min, pct, sc2Adj, emMin - sc2Adj, pctAdj);
            }
        }
    }

    @Test
    void pinnedVsFixedWorkerComparison() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        System.out.println("=== Fixed workers vs Pinned injection ===");

        io.quarkmind.sc2.playbook.CalibrationBaseline baseline;
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-single-base-probes.json")) {
            baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
        }
        java.util.List<io.quarkmind.sc2.playbook.CalibrationSnapshot> sc2           = baseline.snapshots();
        int                                                           tickLimit     = sc2.size();
        int                                                           sc2T150Probes = sc2.get(150).units().getOrDefault("PROBE", 0);
        System.out.printf("SC2 baseline: tick 304=%d minerals, tick 150=%d, %d->36 probes%n",
                          sc2.get(tickLimit - 1).minerals(), sc2.get(150).minerals(), sc2T150Probes);

        for (int fixedWorkers : new int[]{0, 24, 36}) {
            EmulatedGame game = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                      .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                                                 new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                                                      .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            boolean isPinned          = fixedWorkers == 0;
            int     sc2ProbesInjected = 0;
            int     sc2StartProbes    = sc2.get(0).units().getOrDefault("PROBE", 0);
            int     sc2FinalProbes    = sc2.get(tickLimit - 1).units().getOrDefault("PROBE", 0);
            int     sc2TotalToInject  = sc2FinalProbes - sc2StartProbes;
            int     sc2ProbeHighWater = sc2StartProbes;
            if (!isPinned) {
                for (int i = 0; i < fixedWorkers - 12; i++) {
                    game.injectWorkerForPinnedCalibration();
                }
            }
            for (int tick = 0; tick < tickLimit; tick++) {
                game.tick();
                game.processDeferredResearchIntents();
                if (isPinned && tick + 1 < tickLimit) {
                    io.quarkmind.sc2.playbook.CalibrationSnapshot next       = sc2.get(tick + 1);
                    int                                           nextProbes = next.units().getOrDefault("PROBE", 0);
                    if (nextProbes > sc2ProbeHighWater && sc2ProbesInjected < sc2TotalToInject) {
                        int toInject = Math.min(nextProbes - sc2ProbeHighWater, sc2TotalToInject - sc2ProbesInjected);
                        for (int p = 0; p < toInject; p++) {game.injectWorkerForPinnedCalibration();}
                        sc2ProbesInjected += toInject;
                        sc2ProbeHighWater = nextProbes;
                    }
                }
            }
            int    finalMins = game.snapshot().minerals();
            int    workers   = isPinned ? 36 : fixedWorkers;
            String label     = isPinned ? "Pinned (12->36, no bldg)" : String.format("Fixed %dw (no bldg)", fixedWorkers);
            System.out.printf("%-30s: minerals=%d  vs SC2=%+d (%.1f%%)%n",
                              label, finalMins, finalMins - sc2.get(tickLimit - 1).minerals(),
                              (finalMins - sc2.get(tickLimit - 1).minerals()) * 100.0 / sc2.get(tickLimit - 1).minerals());
        }
    }

    @Test
    void traceInjectedWorkerFirstCycles() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        System.out.println("=== Injected Worker First-Cycle Trace ===");
        System.out.println("Injecting worker #13 at tick 14 on TorchesAIE with 12 existing workers");

        io.quarkmind.sc2.playbook.CalibrationBaseline baseline;
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-single-base-probes.json")) {
            baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
        }

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
        var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
        if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
            game.setTerrainGrid(cachedTerrain.get());
            game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
        }
        if (baseline.mineralPatches() != null) {
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                  .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                                             new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                                                  .toList());
        }
        game.setAutoTransferEnabled(false);
        game.reset();

        for (int tick = 0; tick < 14; tick++) {game.tick();}

        int minBefore = game.snapshot().minerals();
        game.injectWorkerForPinnedCalibration();

        String injectedTag = null;
        for (SimulatedWorker w : game.workerSimulation().allWorkers()) {
            if (w.tag.startsWith("unit-")) {
                injectedTag = w.tag;
                break;
            }
        }

        System.out.printf("Injected %s at tick 14, minerals before=%d after=%d%n", injectedTag, minBefore, game.snapshot().minerals());

        System.out.printf("%-6s | %-12s | %-20s | %-10s | %-10s | %-6s%n",
                          "Tick", "Task", "Position", "Target", "Speed", "Cargo");
        System.out.println("-".repeat(80));

        int deliveries   = 0;
        double prevCargo = 0;
        for (int tick = 14; tick < 50; tick++) {
            SimulatedWorker w = null;
            for (SimulatedWorker sw : game.workerSimulation().allWorkers()) {
                if (sw.tag.equals(injectedTag)) {
                    w = sw;
                    break;
                }
            }
            if (w != null) {
                String target = w.targetPosition != null ? String.format("(%.1f,%.1f)", w.targetPosition.x(), w.targetPosition.y()) : "null";
                System.out.printf("%-6d | %-12s | (%.1f, %.1f)        | %-10s | %-10.2f | %-6.0f%n",
                                  tick, w.task, w.position.x(), w.position.y(), target, w.currentSpeed, w.cargo);
                if (w.cargo > 0 && prevCargo == 0) {
                    System.out.println("       *** PICKED UP cargo ***");
                }
                if (w.cargo == 0 && prevCargo > 0) {
                    deliveries++;
                    System.out.printf("       *** DELIVERY #%d ***%n", deliveries);
                }
                prevCargo = w.cargo;
            }
            game.tick();
            if (deliveries >= 3) {break;}
        }
    }

    @Test
    void measureEmCycleTiming12w() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-mineral-baseline-12w.json")) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame game = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                    .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                        new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                    .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            if (baseline.geyserPositions() != null) {
                io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                game.setGeyserResources(baseline.geyserPositions().stream()
                    .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                        new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                    .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                    .toList());
            }

            Map<String, List<double[]>> phaseTiming = new HashMap<>();
            Map<String, double[]> lastPhaseStart = new HashMap<>();
            java.util.concurrent.atomic.AtomicInteger waitCount = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger rerouteCount = new java.util.concurrent.atomic.AtomicInteger();
            Map<String, MineralPatch> prevPatch = new HashMap<>();

            game.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
                @Override public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {
                    if (to == WorkerPhase.WAITING_FOR_PATCH) waitCount.incrementAndGet();
                    lastPhaseStart.computeIfAbsent(tag, k -> new double[]{loop, from.ordinal()});
                    double[] prev = lastPhaseStart.get(tag);
                    double duration = loop - prev[0];
                    if (duration > 0) {
                        phaseTiming.computeIfAbsent(from.name(), k -> new ArrayList<>())
                            .add(new double[]{duration, loop});
                    }
                    lastPhaseStart.put(tag, new double[]{loop, to.ordinal()});
                }
                @Override public void onDelivery(String tag, double minerals, double loop) {}
            });

            for (int tick = 0; tick < 304; tick++) {
                game.tick();
                for (SimulatedWorker w : game.workerSimulation().allWorkers()) {
                    if (w.assignedGasSlot != null) continue;
                    MineralPatch prev = prevPatch.get(w.tag);
                    if (w.assignedPatch != null && prev != null && w.assignedPatch != prev) rerouteCount.incrementAndGet();
                    prevPatch.put(w.tag, w.assignedPatch);
                }
            }

            System.out.println("=== EM Per-Phase Cycle Timing (12w, TorchesAIE, real terrain) ===");
            System.out.printf("%-20s | %-8s | %-8s | %-8s | %-8s | %-6s%n",
                "Phase", "Mean", "Sigma", "Min", "Max", "Count");
            System.out.println("-".repeat(70));
            for (WorkerPhase phase : WorkerPhase.values()) {
                List<double[]> durations = phaseTiming.getOrDefault(phase.name(), java.util.Collections.emptyList());
                if (durations.isEmpty()) continue;
                List<double[]> warmup = durations.stream().filter(d -> d[1] > 200).toList();
                if (warmup.isEmpty()) warmup = durations;
                double mean = warmup.stream().mapToDouble(d -> d[0]).average().orElse(0);
                double sigma = Math.sqrt(warmup.stream().mapToDouble(d -> (d[0]-mean)*(d[0]-mean)).average().orElse(0));
                double min = warmup.stream().mapToDouble(d -> d[0]).min().orElse(0);
                double max = warmup.stream().mapToDouble(d -> d[0]).max().orElse(0);
                System.out.printf("%-20s | %8.1f | %8.1f | %8.1f | %8.1f | %-6d%n",
                    phase.name(), mean, sigma, min, max, warmup.size());
            }
            int sc2Minerals = baseline.snapshots().get(Math.min(303, baseline.snapshots().size()-1)).minerals();
            int emMinerals = io.quarkmind.sc2.playbook.CalibrationSnapshot.from(303, game.snapshot()).minerals();
            System.out.printf("%nTotal minerals: %d (SC2: %d, delta: %+.1f%%)%n", emMinerals, sc2Minerals,
                (emMinerals - sc2Minerals) * 100.0 / sc2Minerals);
            System.out.printf("Wait events: %d, Reroute events: %d%n", waitCount.get(), rerouteCount.get());

            List<double[]> walkToPatch = phaseTiming.getOrDefault("WALKING_TO_PATCH", java.util.Collections.emptyList());
            List<double[]> waiting = phaseTiming.getOrDefault("WAITING_FOR_PATCH", java.util.Collections.emptyList());
            List<double[]> mining = phaseTiming.getOrDefault("MINING", java.util.Collections.emptyList());
            List<double[]> walkToBase = phaseTiming.getOrDefault("WALKING_TO_BASE", java.util.Collections.emptyList());
            double wtp = walkToPatch.stream().filter(d->d[1]>200).mapToDouble(d->d[0]).average().orElse(0);
            double wt = waiting.stream().filter(d->d[1]>200).mapToDouble(d->d[0]).average().orElse(0);
            double mine = mining.stream().filter(d->d[1]>200).mapToDouble(d->d[0]).average().orElse(0);
            double wtb = walkToBase.stream().filter(d->d[1]>200).mapToDouble(d->d[0]).average().orElse(0);
            System.out.printf("%nEstimated cycle: walk(%.0f) + wait(%.0f) + mine(%.0f) + walk_back(%.0f) = %.0f loops%n",
                wtp, wt, mine, wtb, wtp+wt+mine+wtb);
        }
    }

    @Test
    void measureEmCycleTiming8w() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-mineral-baseline-8w.json")) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame game = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                    .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                        new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                    .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            var workers = game.snapshot().myUnits().stream()
                .filter(u -> io.quarkmind.domain.SC2Data.isWorker(u.type())).limit(4).toList();
            for (var w : workers) game.debugKillUnit(w.tag());

            Map<String, List<double[]>> phaseTiming = new HashMap<>();
            Map<String, double[]> lastPhaseStart = new HashMap<>();
            game.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
                @Override public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {
                    lastPhaseStart.computeIfAbsent(tag, k -> new double[]{loop, from.ordinal()});
                    double[] prev = lastPhaseStart.get(tag);
                    double duration = loop - prev[0];
                    if (duration > 0) {
                        phaseTiming.computeIfAbsent(from.name(), k -> new ArrayList<>())
                            .add(new double[]{duration, loop});
                    }
                    lastPhaseStart.put(tag, new double[]{loop, to.ordinal()});
                }
                @Override public void onDelivery(String tag, double minerals, double loop) {}
            });

            for (int tick = 0; tick < 304; tick++) game.tick();

            System.out.println("=== EM Per-Phase Cycle Timing (8w, TorchesAIE, real terrain) ===");
            System.out.printf("%-20s | %-8s | %-8s | %-8s | %-8s | %-6s%n",
                "Phase", "Mean", "Sigma", "Min", "Max", "Count");
            System.out.println("-".repeat(70));
            for (WorkerPhase phase : WorkerPhase.values()) {
                List<double[]> durations = phaseTiming.getOrDefault(phase.name(), java.util.Collections.emptyList());
                if (durations.isEmpty()) continue;
                // Skip first cycle (warmup)
                List<double[]> warmup = durations.stream().filter(d -> d[1] > 200).toList();
                if (warmup.isEmpty()) warmup = durations;
                double mean = warmup.stream().mapToDouble(d -> d[0]).average().orElse(0);
                double sigma = Math.sqrt(warmup.stream().mapToDouble(d -> (d[0]-mean)*(d[0]-mean)).average().orElse(0));
                double min = warmup.stream().mapToDouble(d -> d[0]).min().orElse(0);
                double max = warmup.stream().mapToDouble(d -> d[0]).max().orElse(0);
                System.out.printf("%-20s | %8.1f | %8.1f | %8.1f | %8.1f | %-6d%n",
                    phase.name(), mean, sigma, min, max, warmup.size());
            }
            System.out.printf("%nTotal minerals: %d (SC2: 2335)%n",
                io.quarkmind.sc2.playbook.CalibrationSnapshot.from(303, game.snapshot()).minerals());

            // Compute full cycle from delivery-to-delivery
            List<double[]> walkToPatch = phaseTiming.getOrDefault("WALKING_TO_PATCH", java.util.Collections.emptyList());
            List<double[]> mining = phaseTiming.getOrDefault("MINING", java.util.Collections.emptyList());
            List<double[]> walkToBase = phaseTiming.getOrDefault("WALKING_TO_BASE", java.util.Collections.emptyList());
            double wtp = walkToPatch.stream().filter(d->d[1]>200).mapToDouble(d->d[0]).average().orElse(0);
            double mine = mining.stream().filter(d->d[1]>200).mapToDouble(d->d[0]).average().orElse(0);
            double wtb = walkToBase.stream().filter(d->d[1]>200).mapToDouble(d->d[0]).average().orElse(0);
            System.out.printf("%nEstimated cycle: walk(%.0f) + mine(%.0f) + walk_back(%.0f) = %.0f loops%n", wtp, mine, wtb, wtp+mine+wtb);
            System.out.println("SC2 reference: walk(30) + approach+mine(42) + post_pickup(7) + walk_back(31) + deposit(0) = 110 loops");
        }
    }

    @Test
    void measureWaypointCounts() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SimulatedWorker.ARRIVAL_THRESHOLD = 0.5;
        for (String label : new String[]{"8w", "12w"}) {
            String path = label.equals("8w") ? "calibration/cal-mineral-baseline-8w.json" : "calibration/cal-mineral-baseline-12w.json";
            int killCount = label.equals("8w") ? 4 : 0;
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
                io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
                EmulatedGame game = new EmulatedGame();
                game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
                var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
                if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                    game.setTerrainGrid(cachedTerrain.get());
                    game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
                }
                if (baseline.mineralPatches() != null) {
                    game.setMineralPatchResources(baseline.mineralPatches().stream()
                        .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                            new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                        .toList());
                }
                game.setAutoTransferEnabled(false);
                game.reset();
                if (killCount > 0) {
                    var workers = game.snapshot().myUnits().stream()
                        .filter(u -> io.quarkmind.domain.SC2Data.isWorker(u.type())).limit(killCount).toList();
                    for (var w : workers) game.debugKillUnit(w.tag());
                }
                Map<String, List<Integer>> advanceCounts = new HashMap<>();
                game.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
                    @Override public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {}
                    @Override public void onDelivery(String tag, double minerals, double loop) {}
                });
                // Count advances by patching advanceWaypoint tracking — just count via phase transitions
                // Instead, count directly by tracking waypoint state
                java.util.concurrent.atomic.AtomicInteger totalAdvances = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger totalWalks = new java.util.concurrent.atomic.AtomicInteger();
                // Run and count phase transitions - walks with intermediates have WALKING_TO_PATCH→WALKING_TO_PATCH
                // Actually, can't distinguish. Let me just check A* path lengths directly.
                var ws = game.workerSimulation();
                List<Integer> pathLengths = new java.util.ArrayList<>();
                // Instrument setTargetWithPath by checking waypoints after assignment
                for (int tick = 0; tick < 50; tick++) {
                    game.tick();
                    for (SimulatedWorker w : ws.allWorkers()) {
                        if (w.assignedGasSlot != null) continue;
                        if (w.waypoints != null && !w.waypoints.isEmpty()) {
                            pathLengths.add(w.waypoints.size());
                        }
                    }
                }
                int withWaypoints = (int) pathLengths.stream().filter(n -> n > 0).count();
                double avgLen = pathLengths.isEmpty() ? 0 : pathLengths.stream().mapToInt(i -> i).average().orElse(0);
                System.out.printf("%s: %d waypoint checks, %d with intermediate waypoints, avg length %.1f%n",
                    label, pathLengths.size(), withWaypoints, avgLen);
                Map<Integer, Long> hist = pathLengths.stream().collect(java.util.stream.Collectors.groupingBy(i -> i, java.util.stream.Collectors.counting()));
                hist.forEach((k, v) -> System.out.printf("  waypoints=%d: %d%n", k, v));
            }
        }
    }

    @Test
    void saturationCurveAccuracy() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        record Baseline(String name, String path, int killCount) {}
        Baseline[] baselines = {
            new Baseline("4w", "calibration/cal-mineral-baseline-4w.json", 8),
            new Baseline("6w", "calibration/cal-mineral-baseline-6w.json", 6),
            new Baseline("8w", "calibration/cal-mineral-baseline-8w.json", 4),
            new Baseline("10w", "calibration/cal-mineral-baseline-10w.json", 2),
            new Baseline("12w", "calibration/cal-mineral-baseline-12w.json", 0),
        };
        System.out.println("=== Saturation Curve: EM vs SC2 (TorchesAIE, a=4.9) ===");
        System.out.printf("%-6s | %-7s | %-8s | %-8s | %-8s%n", "Name", "w/patch", "SC2 min", "EM min", "Delta");
        System.out.println("-".repeat(50));
        for (Baseline bl : baselines) {
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(bl.path)) {
                io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
                EmulatedGame game = new EmulatedGame();
                game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
                var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
                if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                    game.setTerrainGrid(cachedTerrain.get());
                    game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
                }
                if (baseline.mineralPatches() != null) {
                    game.setMineralPatchResources(baseline.mineralPatches().stream()
                        .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                            new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                        .toList());
                }
                game.setAutoTransferEnabled(false);
                game.reset();
                if (baseline.geyserPositions() != null) {
                    io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                    game.setGeyserResources(baseline.geyserPositions().stream()
                        .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                            new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                        .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                        .toList());
                }
                if (bl.killCount > 0) {
                    var workers = game.snapshot().myUnits().stream()
                        .filter(u -> io.quarkmind.domain.SC2Data.isWorker(u.type())).limit(bl.killCount).toList();
                    for (var w : workers) game.debugKillUnit(w.tag());
                }
                int tickLimit = Math.min(304, baseline.snapshots().size());
                for (int tick = 0; tick < tickLimit; tick++) game.tick();
                int sc2 = baseline.snapshots().get(tickLimit - 1).minerals();
                int em = io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tickLimit - 1, game.snapshot()).minerals();
                int workers = baseline.snapshots().get(tickLimit - 1).units().getOrDefault("PROBE", 0);
                double wpatch = workers / 8.0;
                double pct = sc2 > 0 ? (em - sc2) * 100.0 / sc2 : 0;
                System.out.printf("%-6s | %-7.1f | %-8d | %-8d | %+6.1f%%%n", bl.name, wpatch, sc2, em, pct);
            }
        }
    }

    @Test
    void sweepDodgeScaleForNewPhysics() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        float[] scales = {0.0f, 0.05f, 0.10f, 0.15f, 0.20f, 0.25f};
        System.out.println("=== Low-Saturation Dodge Scale Sweep (a=4.9, threshold=0.17) ===");
        System.out.println("SC2 targets: 8w=2335, 12w=3415");
        System.out.printf("%-10s | %-8s %-8s | %-8s %-8s | %-10s%n",
            "Dodge", "8w EM", "8w Δ%", "12w EM", "12w Δ%", "Combined");
        System.out.println("-".repeat(70));

        float saved = WorkerSimulation.LOW_SATURATION_DODGE_SCALE;
        for (float scale : scales) {
            WorkerSimulation.LOW_SATURATION_DODGE_SCALE = scale;
            try {
                int em8w = run8wBaseline(mapper);
                int em12w = run12wBaseline(mapper);
                double pct8w = (em8w - 2335) * 100.0 / 2335;
                double pct12w = (em12w - 3415) * 100.0 / 3415;
                double combined = Math.abs(pct8w) + Math.abs(pct12w);
                System.out.printf("%-10.2f | %-8d %+6.1f%% | %-8d %+6.1f%% | %-10.1f%n",
                    scale, em8w, pct8w, em12w, pct12w, combined);
            } finally {
                WorkerSimulation.LOW_SATURATION_DODGE_SCALE = saved;
            }
        }
    }

    @Test
    void calibrateStaggerFromFirstDelivery() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        double[] staggers = {5.0, 5.5, 6.0, 6.1, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0};
        double sc2Mean = 156.8, sc2Min = 112, sc2Max = 191, sc2Spread = 79;
        System.out.println("=== Stagger Calibration from First-Delivery Timing ===");
        System.out.println("SC2 initial workers: mean=156.8 loops (7.1t), spread=79 loops (3.6t), min=112, max=191");
        System.out.printf("%-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s%n",
            "Stagger", "Mean", "Min", "Max", "Spread", "Δ mean", "8w min");
        System.out.println("-".repeat(70));
        double savedStagger = SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS;
        for (double s : staggers) {
            SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS = s;
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-mineral-baseline-12w.json")) {
                io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
                EmulatedGame game = new EmulatedGame();
                game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
                var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
                if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                    game.setTerrainGrid(cachedTerrain.get());
                    game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
                }
                if (baseline.mineralPatches() != null) {
                    game.setMineralPatchResources(baseline.mineralPatches().stream()
                        .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                            new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                        .toList());
                }
                game.setAutoTransferEnabled(false);
                game.reset();
                List<Double> deliveryLoops = new java.util.ArrayList<>();
                game.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
                    final Map<String, Boolean> delivered = new HashMap<>();
                    @Override public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {}
                    @Override public void onDelivery(String tag, double minerals, double loop) {
                        if (!delivered.containsKey(tag)) {
                            delivered.put(tag, true);
                            deliveryLoops.add(loop);
                        }
                    }
                });
                for (int tick = 0; tick < 20; tick++) game.tick();
                if (!deliveryLoops.isEmpty()) {
                    double mean = deliveryLoops.stream().mapToDouble(d->d).average().orElse(0);
                    double min = deliveryLoops.stream().mapToDouble(d->d).min().orElse(0);
                    double max = deliveryLoops.stream().mapToDouble(d->d).max().orElse(0);
                    double spread = max - min;
                    // Also get 8w minerals
                    int em8w = run8wBaseline(mapper);
                    System.out.printf("%-8.1f | %8.0f | %8.0f | %8.0f | %8.0f | %+7.0f | %-8d%n",
                        s, mean, min, max, spread, mean - sc2Mean, em8w);
                }
            } finally {
                SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS = savedStagger;
            }
        }
    }

    @Test
    void sweepInitialStagger() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        double[] staggers = {3.0, 4.0, 5.0, 6.0, 6.1, 7.0, 8.0, 9.0, 10.0, 12.0};
        System.out.println("=== Initial Stagger Sweep (8w + 12w, TorchesAIE, a=4.9) ===");
        System.out.println("SC2 targets: 8w=2335, 12w=3415");
        System.out.printf("%-10s | %-8s %-8s | %-8s %-8s | %-10s%n",
            "Stagger", "8w EM", "8w Δ%", "12w EM", "12w Δ%", "Combined");
        System.out.println("-".repeat(70));

        double saved = SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS;
        for (double s : staggers) {
            SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS = s;
            try {
                int em8w = run8wBaseline(mapper);
                int em12w = run12wBaseline(mapper);
                double pct8w = (em8w - 2335) * 100.0 / 2335;
                double pct12w = (em12w - 3415) * 100.0 / 3415;
                double combined = Math.abs(pct8w) + Math.abs(pct12w);
                System.out.printf("%-10.1f | %-8d %+6.1f%% | %-8d %+6.1f%% | %-10.1f%n",
                    s, em8w, pct8w, em12w, pct12w, combined);
            } finally {
                SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS = saved;
            }
        }
    }

    @Test
    void sweepRerouteThreshold() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        double[] thresholds = {22, 30, 44, 55, 66, 88, 110, 200};
        System.out.println("=== Reroute Threshold Sweep (8w + 12w, TorchesAIE, a=4.9) ===");
        System.out.println("SC2 targets: 8w=2335, 12w=3415. Current docking overhead: 22 loops");
        System.out.printf("%-10s | %-8s %-8s | %-8s %-8s | %-10s%n",
            "Threshold", "8w EM", "8w Δ%", "12w EM", "12w Δ%", "Combined");
        System.out.println("-".repeat(70));

        double saved = SimulatedWorker.REROUTE_THRESHOLD_LOOPS;
        for (double t : thresholds) {
            SimulatedWorker.REROUTE_THRESHOLD_LOOPS = t;
            try {
                int em8w = run8wBaseline(mapper);
                int em12w = run12wBaseline(mapper);
                double pct8w = (em8w - 2335) * 100.0 / 2335;
                double pct12w = (em12w - 3415) * 100.0 / 3415;
                double combined = Math.abs(pct8w) + Math.abs(pct12w);
                System.out.printf("%-10.0f | %-8d %+6.1f%% | %-8d %+6.1f%% | %-10.1f%n",
                    t, em8w, pct8w, em12w, pct12w, combined);
            } finally {
                SimulatedWorker.REROUTE_THRESHOLD_LOOPS = saved;
            }
        }
    }

    @Test
    void sweepAccelerationAndThreshold() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        double[] thresholds = {0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5};
        System.out.println("=== Acceleration + Arrival Threshold Sweep (a=4.9, TorchesAIE) ===");
        System.out.println("SC2 measured: accel=4.84 u/s² (n=2239, σ=0.059), arrival speed ~1.3 u/s (~0.17u threshold)");
        System.out.println("SC2 targets: 8w=2335, 12w=3415");
        System.out.printf("%-10s | %-8s %-8s | %-8s %-8s | %-10s%n",
            "Threshold", "8w EM", "8w Δ%", "12w EM", "12w Δ%", "Combined");
        System.out.println("-".repeat(70));

        double savedThreshold = SimulatedWorker.ARRIVAL_THRESHOLD;
        WorkerSimulation.WAYPOINT_SPEED_FACTOR = 1.0;
        try {
            for (double t : thresholds) {
                SimulatedWorker.ARRIVAL_THRESHOLD = t;
                int em8w = run8wBaseline(mapper);
                int em12w = run12wBaseline(mapper);
                double pct8w = (em8w - 2335) * 100.0 / 2335;
                double pct12w = (em12w - 3415) * 100.0 / 3415;
                double combined = Math.abs(pct8w) + Math.abs(pct12w);
                System.out.printf("%-10.2f | %-8d %+6.1f%% | %-8d %+6.1f%% | %-10.1f%n",
                    t, em8w, pct8w, em12w, pct12w, combined);
            }
        } finally {
            SimulatedWorker.ARRIVAL_THRESHOLD = savedThreshold;
        }
    }

    @Test
    void sweepWaypointSpeedFactor() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        double[] factors = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        System.out.println("=== Waypoint Speed Factor Sweep (8w + 12w, TorchesAIE) ===");
        System.out.println("SC2 targets: 8w=2335, 12w=3415");
        System.out.printf("%-8s | %-8s %-8s | %-8s %-8s | %-10s%n",
            "Factor", "8w EM", "8w Δ%", "12w EM", "12w Δ%", "Combined");
        System.out.println("-".repeat(70));

        double saved = WorkerSimulation.WAYPOINT_SPEED_FACTOR;
        for (double factor : factors) {
            WorkerSimulation.WAYPOINT_SPEED_FACTOR = factor;
            try {
                int em8w = run8wBaseline(mapper);
                int em12w = run12wBaseline(mapper);
                double pct8w = (em8w - 2335) * 100.0 / 2335;
                double pct12w = (em12w - 3415) * 100.0 / 3415;
                double combined = Math.abs(pct8w) + Math.abs(pct12w);
                System.out.printf("%-8.1f | %-8d %+6.1f%% | %-8d %+6.1f%% | %-10.1f%n",
                    factor, em8w, pct8w, em12w, pct12w, combined);
            } finally {
                WorkerSimulation.WAYPOINT_SPEED_FACTOR = saved;
            }
        }
    }

    private int run12wBaseline(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-mineral-baseline-12w.json")) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame game = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                    .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                        new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                    .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            if (baseline.geyserPositions() != null) {
                io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                game.setGeyserResources(baseline.geyserPositions().stream()
                    .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                        new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                    .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                    .toList());
            }
            int tickLimit = Math.min(304, baseline.snapshots().size());
            for (int tick = 0; tick < tickLimit; tick++) {
                game.tick();
            }
            return io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tickLimit - 1, game.snapshot()).minerals();
        }
    }

    @Test
    void isolate768WithOffsetCoords() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        System.out.println("=== #768 Isolation: Real Terrain vs Offset Coordinates ===");
        System.out.println("SC2 targets: 8w=2335, 12w=3415");
        System.out.printf("%-40s | %-8s %-8s | %-8s %-8s%n", "Config", "8w", "8w Δ%", "12w", "12w Δ%");
        System.out.println("-".repeat(80));

        double savedFactor = WorkerSimulation.WAYPOINT_SPEED_FACTOR;
        try {
            WorkerSimulation.WAYPOINT_SPEED_FACTOR = 1.0;
            int em8w = run8wBaseline(mapper);
            int em12w = run12wBaseline(mapper);
            System.out.printf("%-40s | %-8d %+6.1f%% | %-8d %+6.1f%%%n", "Real terrain (current)",
                em8w, (em8w-2335)*100.0/2335, em12w, (em12w-3415)*100.0/3415);

            int em8wOff = runBaselineWithOffset(mapper, "calibration/cal-mineral-baseline-8w.json", 4);
            int em12wOff = runBaselineWithOffset(mapper, "calibration/cal-mineral-baseline-12w.json", 0);
            System.out.printf("%-40s | %-8d %+6.1f%% | %-8d %+6.1f%%%n", "Offset coords (like main)",
                em8wOff, (em8wOff-2335)*100.0/2335, em12wOff, (em12wOff-3415)*100.0/3415);
        } finally {
            WorkerSimulation.WAYPOINT_SPEED_FACTOR = savedFactor;
        }
    }

    private int runBaselineWithOffset(com.fasterxml.jackson.databind.ObjectMapper mapper, String path, int killCount) throws Exception {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame game = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            if (baseline.basePosition() != null && baseline.mineralPatches() != null) {
                float offX = 8f - baseline.basePosition()[0];
                float offY = 8f - baseline.basePosition()[1];
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                    .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                        new io.quarkmind.domain.Point2d(p.x() + offX, p.y() + offY), p.amount(), p.rich()))
                    .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            if (baseline.geyserPositions() != null) {
                float offX = 8f - baseline.basePosition()[0];
                float offY = 8f - baseline.basePosition()[1];
                game.setGeyserResources(baseline.geyserPositions().stream()
                    .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                        new io.quarkmind.domain.Point2d(g.x() + offX, g.y() + offY), g.amount()))
                    .filter(g -> g.position().distanceTo(new io.quarkmind.domain.Point2d(8, 8)) <= 12.0)
                    .toList());
            }
            if (killCount > 0) {
                var workers = game.snapshot().myUnits().stream()
                    .filter(u -> io.quarkmind.domain.SC2Data.isWorker(u.type())).limit(killCount).toList();
                for (var w : workers) game.debugKillUnit(w.tag());
            }
            int tickLimit = Math.min(304, baseline.snapshots().size());
            for (int tick = 0; tick < tickLimit; tick++) game.tick();
            return io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tickLimit - 1, game.snapshot()).minerals();
        }
    }

    @Test
    void isolate768Regression() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        System.out.println("=== #768 Regression Isolation (8w, TorchesAIE) ===");
        System.out.println("SC2 baseline: 2335 minerals at tick 304");
        System.out.printf("%-40s | %-8s | %-8s%n", "Config", "EM min", "Delta");
        System.out.println("-".repeat(65));

        double savedDeposit = SimulatedWorker.DEPOSIT_DURATION_LOOPS;
        double savedPostPickup = SimulatedWorker.POST_PICKUP_LOOPS;
        boolean savedSnap = WorkerSimulation.TILE_CENTER_SNAP;
        boolean savedSpeedReset = WorkerSimulation.WAYPOINT_SPEED_RESET;
        float savedRadius = SimulatedWorker.MINERAL_PATCH_OBSTACLE_RADIUS;

        record Config(String label, double deposit, double postPickup, boolean snap, boolean speedReset, float obstacleR) {}
        Config[] configs = {
            new Config("Branch (current)", 0, 7, true, true, 0.5f),
            new Config("Main-like (deposit=12, no post, no snap)", 12, 0, false, true, 0.3f),
            new Config("deposit=12 only", 12, 7, true, true, 0.5f),
            new Config("no tile-center snap only", 0, 7, false, true, 0.5f),
            new Config("no waypoint speed reset only", 0, 7, true, false, 0.5f),
            new Config("obstacle radius 0.3 only", 0, 7, true, true, 0.3f),
            new Config("snap=off + speedReset=off", 0, 7, false, false, 0.5f),
            new Config("snap=off + deposit=12", 12, 7, false, true, 0.5f),
        };

        for (Config cfg : configs) {
            SimulatedWorker.DEPOSIT_DURATION_LOOPS = cfg.deposit;
            SimulatedWorker.POST_PICKUP_LOOPS = cfg.postPickup;
            WorkerSimulation.TILE_CENTER_SNAP = cfg.snap;
            WorkerSimulation.WAYPOINT_SPEED_RESET = cfg.speedReset;
            SimulatedWorker.MINERAL_PATCH_OBSTACLE_RADIUS = cfg.obstacleR;
            try {
                int em = run8wBaseline(mapper);
                double pct = (em - 2335) * 100.0 / 2335;
                System.out.printf("%-40s | %-8d | %+6.1f%%%n", cfg.label, em, pct);
            } finally {
                SimulatedWorker.DEPOSIT_DURATION_LOOPS = savedDeposit;
                SimulatedWorker.POST_PICKUP_LOOPS = savedPostPickup;
                WorkerSimulation.TILE_CENTER_SNAP = savedSnap;
                WorkerSimulation.WAYPOINT_SPEED_RESET = savedSpeedReset;
                SimulatedWorker.MINERAL_PATCH_OBSTACLE_RADIUS = savedRadius;
            }
        }
    }

    private int run8wBaseline(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("calibration/cal-mineral-baseline-8w.json")) {
            io.quarkmind.sc2.playbook.CalibrationBaseline baseline = mapper.readValue(is, io.quarkmind.sc2.playbook.CalibrationBaseline.class);
            EmulatedGame game = new EmulatedGame();
            game.setPlayerRaceModel(RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
            var cachedTerrain = io.quarkmind.sc2.replay.MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
            if (cachedTerrain.isPresent() && baseline.basePosition() != null) {
                game.setTerrainGrid(cachedTerrain.get());
                game.setInitialBasePosition(new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]));
            }
            if (baseline.mineralPatches() != null) {
                game.setMineralPatchResources(baseline.mineralPatches().stream()
                    .map(p -> new io.quarkmind.domain.Resource("cal-" + baseline.mineralPatches().indexOf(p),
                        new io.quarkmind.domain.Point2d(p.x(), p.y()), p.amount(), p.rich()))
                    .toList());
            }
            game.setAutoTransferEnabled(false);
            game.reset();
            if (baseline.geyserPositions() != null) {
                io.quarkmind.domain.Point2d emBase = new io.quarkmind.domain.Point2d(baseline.basePosition()[0], baseline.basePosition()[1]);
                game.setGeyserResources(baseline.geyserPositions().stream()
                    .map(g -> new io.quarkmind.domain.Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                        new io.quarkmind.domain.Point2d(g.x(), g.y()), g.amount()))
                    .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                    .toList());
            }
            var workers = game.snapshot().myUnits().stream()
                .filter(u -> io.quarkmind.domain.SC2Data.isWorker(u.type()))
                .limit(4).toList();
            for (var w : workers) game.debugKillUnit(w.tag());
            int tickLimit = Math.min(304, baseline.snapshots().size());
            for (int tick = 0; tick < tickLimit; tick++) {
                game.tick();
            }
            return io.quarkmind.sc2.playbook.CalibrationSnapshot.from(tickLimit - 1, game.snapshot()).minerals();
        }
    }

}
