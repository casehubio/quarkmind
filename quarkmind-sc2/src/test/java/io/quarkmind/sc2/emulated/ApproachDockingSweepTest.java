package io.quarkmind.sc2.emulated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import io.quarkmind.sc2.playbook.SC2DeliveryHandler;
import io.quarkmind.sc2.playbook.SC2PlaybookRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag("diagnostic")
class ApproachDockingSweepTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @AfterEach
    void resetDefaults() {
        SimulatedWorker.resetToDefaults();
    }

    @Test
    void sweep8wAnd12w() throws IOException {
        CalibrationBaseline sc2_8w  = loadBaseline("calibration/cal-mineral-baseline-8w.json");
        CalibrationBaseline sc2_12w = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        CalibrationBaseline sc2_16w = loadBaseline("calibration/cal-mineral-saturated-16w.json");

        int sc2_8  = sc2_8w.snapshots().get(sc2_8w.snapshots().size() - 1).minerals();
        int sc2_12 = sc2_12w.snapshots().get(sc2_12w.snapshots().size() - 1).minerals();
        int sc2_16 = sc2_16w.snapshots().get(sc2_16w.snapshots().size() - 1).minerals();

        System.out.printf("%-6s %-6s %-6s | %6s %6s %6s | %6s %6s %6s | %6s %6s %6s | %5s%n",
                          "appr", "dep", "lock", "EM8w", "SC8w", "gap%", "EM12w", "SC12w", "gap%", "EM16w", "SC16w", "gap%", "best8");
        System.out.println("-".repeat(105));

        double bestGap8     = Double.MAX_VALUE;
        double bestApproach = 0, bestDeposit = 0, bestLockout = 0;

        for (double approach = 11.5; approach <= 28.0; approach += 0.5) {
            for (double deposit = 7.0; deposit <= 14.0; deposit += 1.0) {
                for (double lockout = 0; lockout <= 22.0; lockout += 1.0) {
                    SimulatedWorker.APPROACH_DOCKING_LOOPS       = approach;
                    SimulatedWorker.DEPOSIT_DURATION_LOOPS       = deposit;
                    SimulatedWorker.PATCH_DOCKING_OVERHEAD_LOOPS = lockout;

                    int em8w  = runPlaybook("playbooks/cal-mineral-baseline-8w.yaml", Race.PROTOSS, sc2_8w);
                    int em12w = runPlaybook("playbooks/cal-mineral-baseline-12w.yaml", Race.PROTOSS, sc2_12w);
                    int em16w = runPlaybook("playbooks/cal-mineral-saturated-16w.yaml", Race.PROTOSS, sc2_16w);

                    double gap8  = (em8w - sc2_8) * 100.0 / sc2_8;
                    double gap12 = (em12w - sc2_12) * 100.0 / sc2_12;
                    double gap16 = (em16w - sc2_16) * 100.0 / sc2_16;

                    boolean viable = gap12 >= -3.0 && gap12 <= 3.0 && gap16 >= -3.0 && gap16 <= 3.0;
                    if (viable && Math.abs(gap8) < bestGap8) {
                        bestGap8     = Math.abs(gap8);
                        bestApproach = approach;
                        bestDeposit  = deposit;
                        bestLockout  = lockout;
                    }

                    if (viable && Math.abs(gap8) <= 6) {
                        System.out.printf("%-6.1f %-6.1f %-6.1f | %6d %6d %+5.1f%% | %6d %6d %+5.1f%% | %6d %6d %+5.1f%% | %5.1f%n",
                                          approach, deposit, lockout, em8w, sc2_8, gap8, em12w, sc2_12, gap12, em16w, sc2_16, gap16, Math.abs(gap8));
                    }
                }
            }
        }

        System.out.printf("%nBest 8w (with 12w/16w within ±3%%): approach=%.1f deposit=%.1f lockout=%.1f%n",
                          bestApproach, bestDeposit, bestLockout);
        SimulatedWorker.APPROACH_DOCKING_LOOPS       = bestApproach;
        SimulatedWorker.DEPOSIT_DURATION_LOOPS       = bestDeposit;
        SimulatedWorker.PATCH_DOCKING_OVERHEAD_LOOPS = bestLockout;
        int em8  = runPlaybook("playbooks/cal-mineral-baseline-8w.yaml", Race.PROTOSS, sc2_8w);
        int em12 = runPlaybook("playbooks/cal-mineral-baseline-12w.yaml", Race.PROTOSS, sc2_12w);
        int em16 = runPlaybook("playbooks/cal-mineral-saturated-16w.yaml", Race.PROTOSS, sc2_16w);
        System.out.printf("  8w:  EM=%d SC2=%d gap=%+.1f%%%n", em8, sc2_8, (em8 - sc2_8) * 100.0 / sc2_8);
        System.out.printf("  12w: EM=%d SC2=%d gap=%+.1f%%%n", em12, sc2_12, (em12 - sc2_12) * 100.0 / sc2_12);
        System.out.printf("  16w: EM=%d SC2=%d gap=%+.1f%%%n", em16, sc2_16, (em16 - sc2_16) * 100.0 / sc2_16);
    }

    @Test
    void testTargetConstants() throws IOException {
        CalibrationBaseline sc2_8w  = loadBaseline("calibration/cal-mineral-baseline-8w.json");
        CalibrationBaseline sc2_12w = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        CalibrationBaseline sc2_16w = loadBaseline("calibration/cal-mineral-saturated-16w.json");
        CalibrationBaseline sc2_24w = loadBaseline("calibration/cal-mineral-saturated-24w.json");
        CalibrationBaseline sc2_3wg = loadBaseline("calibration/cal-gas-baseline-3w.json");
        CalibrationBaseline sc2_6wg = loadBaseline("calibration/cal-gas-dual-6w.json");

        int s8  = sc2_8w.snapshots().get(sc2_8w.snapshots().size() - 1).minerals();
        int s12 = sc2_12w.snapshots().get(sc2_12w.snapshots().size() - 1).minerals();
        int s16 = sc2_16w.snapshots().get(sc2_16w.snapshots().size() - 1).minerals();
        int s24 = sc2_24w.snapshots().get(sc2_24w.snapshots().size() - 1).minerals();
        int s3m = sc2_3wg.snapshots().get(sc2_3wg.snapshots().size() - 1).minerals();
        int s3v = sc2_3wg.snapshots().get(sc2_3wg.snapshots().size() - 1).vespene();
        int s6m = sc2_6wg.snapshots().get(sc2_6wg.snapshots().size() - 1).minerals();
        int s6v = sc2_6wg.snapshots().get(sc2_6wg.snapshots().size() - 1).vespene();

        double[][] configs = {
                {11.5, 7.0, 0.96},
                {24.5, 12.0, 1.0},
                {23.5, 12.0, 0.985},
                {24.5, 12.0, 0.99},
                {25.0, 12.0, 1.0},
                };

        System.out.printf("%-6s %-6s %-6s | %7s %7s %7s %7s | %7s %7s %7s %7s%n",
                          "appr", "dep", "coll", "8w%", "12w%", "16w%", "24w%", "3wm%", "3wv%", "6wm%", "6wv%");
        System.out.println("-".repeat(95));

        for (double[] c : configs) {
            SimulatedWorker.APPROACH_DOCKING_LOOPS       = c[0];
            SimulatedWorker.DEPOSIT_DURATION_LOOPS       = c[1];
            SimulatedWorker.COLLISION_SPEED_FACTOR       = c[2];
            SimulatedWorker.PATCH_DOCKING_OVERHEAD_LOOPS = 22.0;

            int e8  = runPlaybook("playbooks/cal-mineral-baseline-8w.yaml", Race.PROTOSS, sc2_8w);
            int e12 = runPlaybook("playbooks/cal-mineral-baseline-12w.yaml", Race.PROTOSS, sc2_12w);
            int e16 = runPlaybook("playbooks/cal-mineral-saturated-16w.yaml", Race.PROTOSS, sc2_16w);
            int e24 = runPlaybook("playbooks/cal-mineral-saturated-24w.yaml", Race.PROTOSS, sc2_24w);
            int e3m = runPlaybook("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS, sc2_3wg);
            int e3v = runPlaybookVespene("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS, sc2_3wg);
            int e6m = runPlaybook("playbooks/cal-gas-dual-6w.yaml", Race.PROTOSS, sc2_6wg);
            int e6v = runPlaybookVespene("playbooks/cal-gas-dual-6w.yaml", Race.PROTOSS, sc2_6wg);

            System.out.printf("%-6.1f %-6.1f %-6.3f | %+6.1f%% %+6.1f%% %+6.1f%% %+6.1f%% | %+6.1f%% %+6.1f%% %+6.1f%% %+6.1f%%%n",
                              c[0], c[1], c[2],
                              (e8 - s8) * 100.0 / s8, (e12 - s12) * 100.0 / s12, (e16 - s16) * 100.0 / s16, (e24 - s24) * 100.0 / s24,
                              (e3m - s3m) * 100.0 / s3m, (e3v - s3v) * 100.0 / s3v, (e6m - s6m) * 100.0 / s6m, (e6v - s6v) * 100.0 / s6v);
        }
    }

    @Test
    void sweepApproachAndCollision() throws IOException {
        CalibrationBaseline sc2_8w  = loadBaseline("calibration/cal-mineral-baseline-8w.json");
        CalibrationBaseline sc2_12w = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        CalibrationBaseline sc2_16w = loadBaseline("calibration/cal-mineral-saturated-16w.json");

        int sc2_8  = sc2_8w.snapshots().get(sc2_8w.snapshots().size() - 1).minerals();
        int sc2_12 = sc2_12w.snapshots().get(sc2_12w.snapshots().size() - 1).minerals();
        int sc2_16 = sc2_16w.snapshots().get(sc2_16w.snapshots().size() - 1).minerals();

        System.out.printf("%-6s %-6s %-6s | %7s %7s %7s | %5s%n",
                          "appr", "dep", "coll", "8w%", "12w%", "16w%", "comb");
        System.out.println("-".repeat(65));

        double bestCombined = Double.MAX_VALUE;
        double bestA        = 0, bestD = 0, bestC = 0;

        for (double approach = 20.0; approach <= 26.0; approach += 0.5) {
            for (double collision = 0.975; collision <= 1.001; collision += 0.005) {
                for (double deposit = 9.0; deposit <= 12.0; deposit += 1.0) {
                SimulatedWorker.APPROACH_DOCKING_LOOPS       = approach;
                SimulatedWorker.DEPOSIT_DURATION_LOOPS       = deposit;
                SimulatedWorker.COLLISION_SPEED_FACTOR       = collision;
                SimulatedWorker.PATCH_DOCKING_OVERHEAD_LOOPS = 22.0;

                int em8  = runPlaybook("playbooks/cal-mineral-baseline-8w.yaml", Race.PROTOSS, sc2_8w);
                int em12 = runPlaybook("playbooks/cal-mineral-baseline-12w.yaml", Race.PROTOSS, sc2_12w);
                int em16 = runPlaybook("playbooks/cal-mineral-saturated-16w.yaml", Race.PROTOSS, sc2_16w);

                double g8       = (em8 - sc2_8) * 100.0 / sc2_8;
                double g12      = (em12 - sc2_12) * 100.0 / sc2_12;
                double g16      = (em16 - sc2_16) * 100.0 / sc2_16;
                double combined = Math.abs(g8) + Math.abs(g12) + Math.abs(g16);

                if (combined < bestCombined) {
                    bestCombined = combined;
                    bestA        = approach;
                    bestD        = deposit;
                    bestC        = collision;
                }

                boolean good = Math.abs(g8) <= 5 && Math.abs(g12) <= 5 && Math.abs(g16) <= 5;
                if (good) {
                    System.out.printf("%-6.1f %-6.1f %-6.3f | %+6.1f%% %+6.1f%% %+6.1f%% | %5.1f <<<\n",
                                      approach, deposit, collision, g8, g12, g16, combined);
                }
                }
            }
        }

        System.out.printf("%nBest: approach=%.1f deposit=%.1f collision=%.3f (combined=%.1f%%)%n",
                          bestA, bestD, bestC, bestCombined);
        SimulatedWorker.APPROACH_DOCKING_LOOPS = bestA;
        SimulatedWorker.DEPOSIT_DURATION_LOOPS = bestD;
        SimulatedWorker.COLLISION_SPEED_FACTOR = bestC;
        int em8  = runPlaybook("playbooks/cal-mineral-baseline-8w.yaml", Race.PROTOSS, sc2_8w);
        int em12 = runPlaybook("playbooks/cal-mineral-baseline-12w.yaml", Race.PROTOSS, sc2_12w);
        int em16 = runPlaybook("playbooks/cal-mineral-saturated-16w.yaml", Race.PROTOSS, sc2_16w);
        System.out.printf("  8w:  EM=%d SC2=%d gap=%+.1f%%%n", em8, sc2_8, (em8 - sc2_8) * 100.0 / sc2_8);
        System.out.printf("  12w: EM=%d SC2=%d gap=%+.1f%%%n", em12, sc2_12, (em12 - sc2_12) * 100.0 / sc2_12);
        System.out.printf("  16w: EM=%d SC2=%d gap=%+.1f%%%n", em16, sc2_16, (em16 - sc2_16) * 100.0 / sc2_16);
    }

    @Test
    void traceTargetConstants8w() throws IOException {
        CalibrationBaseline sc2_8w = loadBaseline("calibration/cal-mineral-baseline-8w.json");

        SimulatedWorker.APPROACH_DOCKING_LOOPS = 24.0;
        SimulatedWorker.DEPOSIT_DURATION_LOOPS = 10.0;
        SimulatedWorker.COLLISION_SPEED_FACTOR = 0.99;

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        if (sc2_8w.basePosition() != null && sc2_8w.mineralPatches() != null) {
            float fx = 8f - sc2_8w.basePosition()[0];
            float fy = 8f - sc2_8w.basePosition()[1];
            game.setMineralPatchResources(sc2_8w.mineralPatches().stream()
                                                .map(p -> new io.quarkmind.domain.Resource("cal-" + sc2_8w.mineralPatches().indexOf(p),
                                                                                           new io.quarkmind.domain.Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                                                .toList());
        }
        game.reset();

        SC2DeliveryHandler                      handler = new SC2DeliveryHandler(game);
        String                                  yaml    = SC2PlaybookRunner.loadResource("playbooks/cal-mineral-baseline-8w.yaml");
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(yaml);
        } catch (Exception e) {throw new RuntimeException(e);}
        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);
        boolean[]                            fired = new boolean[steps.size()];

        System.out.printf("APPROACH_DOCKING_LOOPS static: %.1f%n", SimulatedWorker.APPROACH_DOCKING_LOOPS);
        System.out.printf("DEPOSIT_DURATION_LOOPS: %.1f%n", SimulatedWorker.DEPOSIT_DURATION_LOOPS);
        System.out.printf("COLLISION_SPEED_FACTOR: %.3f%n", SimulatedWorker.COLLISION_SPEED_FACTOR);
        for (SimulatedWorker w : game.workerSimulation().allWorkers()) {
            if (w.assignedPatch != null) {
                System.out.printf("Worker %s patch approach=%.1f%n", w.tag, w.assignedPatch.approachDockingLoops);
                break;
            }
        }
        int prevMinerals = 50;
        System.out.println("tick | workers | EM min | SC2 min | delta | EM/tick");
        for (int tick = 0; tick < 305; tick++) {
            int emMin   = game.snapshot().minerals();
            int sc2Min  = sc2_8w.snapshots().get(tick).minerals();
            int workers = (int) game.snapshot().myUnits().stream().filter(u -> io.quarkmind.domain.SC2Data.isWorker(u.type())).count();
            int emDelta = emMin - prevMinerals;
            prevMinerals = emMin;

            if (tick <= 15 || tick % 50 == 0 || tick == 304) {
                System.out.printf("%4d | %7d | %6d | %7d | %+5d | %+5d%n",
                                  tick, workers, emMin, sc2Min, emMin - sc2Min, emDelta);
            }

            game.tick();
            int supplyUsed = (int) game.snapshot().supplyUsed();
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                if (step.shouldFire(tick, supplyUsed) && (!fired[i] || step.loop())) {
                    java.util.Map<String, Object> data = new java.util.HashMap<>(step.params());
                    data.put("action", step.action());
                    handler.execute(step.action() + "-" + tick, data, null);
                    fired[i] = true;
                }
            }
        }
    }


    private int runPlaybook(String resource, Race race, CalibrationBaseline baseline) {
        String   yaml = SC2PlaybookRunner.loadResource(resource);
        JsonNode root;
        try {root = YAML_MAPPER.readTree(yaml);} catch (Exception e) {throw new RuntimeException(e);}

        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        if (baseline.basePosition() != null && baseline.mineralPatches() != null) {
            float fx = 8f - baseline.basePosition()[0];
            float fy = 8f - baseline.basePosition()[1];
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                  .map(p -> new Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                         new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                                                  .toList());
        }
        game.reset();
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        int       tickLimit = Math.min(baseline.tickCount(), baseline.snapshots().size());
        boolean[] fired     = new boolean[steps.size()];

        for (int tick = 0; tick < tickLimit; tick++) {
            game.tick();
            int supplyUsed = (int) game.snapshot().supplyUsed();
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step    = steps.get(i);
                boolean                        canFire = step.shouldFire(tick, supplyUsed) && (!fired[i] || step.loop());
                if (canFire) {
                    Map<String, Object> data = new HashMap<>(step.params());
                    data.put("action", step.action());
                    var outcome = handler.execute(step.action() + "-" + tick, data, null);
                    if (step.supplyTrigger() > 0) {
                        if (outcome.success()) {fired[i] = true;}
                    } else {
                        fired[i] = true;
                    }
                }
            }
        }
        return game.snapshot().minerals();
    }

    private int runPlaybookVespene(String resource, Race race, CalibrationBaseline baseline) {return runPlaybookGame(resource, race, baseline).snapshot().vespene();}

    private EmulatedGame runPlaybookGame(String resource, Race race, CalibrationBaseline baseline) {
        String   yaml = SC2PlaybookRunner.loadResource(resource);
        JsonNode root;
        try {root = YAML_MAPPER.readTree(yaml);} catch (Exception e) {throw new RuntimeException(e);}

        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        if (baseline.basePosition() != null && baseline.mineralPatches() != null) {
            float fx = 8f - baseline.basePosition()[0];
            float fy = 8f - baseline.basePosition()[1];
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                                                  .map(p -> new Resource("cal-" + baseline.mineralPatches().indexOf(p),
                                                                         new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                                                  .toList());
        }
        game.reset();
        if (baseline.basePosition() != null && baseline.geyserPositions() != null) {
            float   fx     = 8f - baseline.basePosition()[0];
            float   fy     = 8f - baseline.basePosition()[1];
            Point2d emBase = new Point2d(8, 8);
            game.setGeyserResources(baseline.geyserPositions().stream()
                                            .map(g -> new Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                                                                   new Point2d(g.x() + fx, g.y() + fy), g.amount()))
                                            .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                                            .toList());
        }
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        int       tickLimit = Math.min(baseline.tickCount(), baseline.snapshots().size());
        boolean[] fired     = new boolean[steps.size()];
        int       gasIdx    = 0;

        for (int tick = 0; tick < tickLimit; tick++) {
            game.tick();
            game.processDeferredResearchIntents();
            int supplyUsed = (int) game.snapshot().supplyUsed();
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step    = steps.get(i);
                boolean                        canFire = step.shouldFire(tick, supplyUsed) && (!fired[i] || step.loop());
                if (canFire) {
                    if ("build".equals(step.action())) {
                        io.quarkmind.domain.BuildingType bt = io.quarkmind.domain.BuildingType.valueOf((String) step.params().get("build"));
                        if (io.quarkmind.domain.SC2Data.isGasBuilding(bt)) {
                            var geysers = game.snapshot().geysers();
                            Point2d pos = (geysers != null && gasIdx < geysers.size())
                                          ? geysers.get(gasIdx++).position() : new Point2d(16, 12);
                            game.debugCreateBuilding(bt, pos);
                            fired[i] = true;
                            continue;
                        }
                    }
                    Map<String, Object> data = new HashMap<>(step.params());
                    data.put("action", step.action());
                    var outcome = handler.execute(step.action() + "-" + tick, data, null);
                    if (step.supplyTrigger() > 0) {
                        if (outcome.success()) {fired[i] = true;}
                    } else {
                        fired[i] = true;
                    }
                }
            }
        }
        return game;
    }


    @Test
    void sweepGasApproachDocking() throws IOException {
        CalibrationBaseline sc2_3wg = loadBaseline("calibration/cal-gas-baseline-3w.json");
        CalibrationBaseline sc2_6wg = loadBaseline("calibration/cal-gas-dual-6w.json");

        int s3v = sc2_3wg.snapshots().get(sc2_3wg.snapshots().size() - 1).vespene();
        int s6v = sc2_6wg.snapshots().get(sc2_6wg.snapshots().size() - 1).vespene();
        int s3m = sc2_3wg.snapshots().get(sc2_3wg.snapshots().size() - 1).minerals();
        int s6m = sc2_6wg.snapshots().get(sc2_6wg.snapshots().size() - 1).minerals();

        System.out.println("=== Gas Approach Docking Sweep — 3w and 6w dual-run baselines ===");
        System.out.printf("SC2 targets: 3w vespene=%d minerals=%d, 6w vespene=%d minerals=%d%n", s3v, s3m, s6v, s6m);
        System.out.printf("%-8s | %6s %6s %7s | %6s %6s %7s | %6s %6s %7s | %5s%n",
                          "gasDock", "EM3v", "SC3v", "gap3v%", "EM6v", "SC6v", "gap6v%", "EM3m", "SC3m", "gap3m%", "comb");
        System.out.println("-".repeat(95));

        double bestCombined = Double.MAX_VALUE;
        double bestDocking  = 0;

        for (double docking = 0; docking <= 25.0; docking += 0.5) {
            SimulatedWorker.setGasApproachDockingLoops(docking);

            int em3v = runPlaybookVespene("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS, sc2_3wg);
            int em6v = runPlaybookVespene("playbooks/cal-gas-dual-6w.yaml", Race.PROTOSS, sc2_6wg);
            int em3m = runPlaybook("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS, sc2_3wg);

            double gap3v    = (em3v - s3v) * 100.0 / s3v;
            double gap6v    = (em6v - s6v) * 100.0 / s6v;
            double gap3m    = (em3m - s3m) * 100.0 / s3m;
            double combined = Math.abs(gap3v) + Math.abs(gap6v);

            if (combined < bestCombined) {
                bestCombined = combined;
                bestDocking  = docking;
            }

            System.out.printf("%-8.1f | %6d %6d %+6.1f%% | %6d %6d %+6.1f%% | %6d %6d %+6.1f%% | %5.1f%n",
                              docking, em3v, s3v, gap3v, em6v, s6v, gap6v, em3m, s3m, gap3m, combined);
        }

        System.out.printf("%nBest combined (3w+6w vespene): GAS_APPROACH_DOCKING=%.1f (combined=%.1f%%)%n", bestDocking, bestCombined);

        SimulatedWorker.setGasApproachDockingLoops(bestDocking);
        int best3v = runPlaybookVespene("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS, sc2_3wg);
        int best6v = runPlaybookVespene("playbooks/cal-gas-dual-6w.yaml", Race.PROTOSS, sc2_6wg);
        int best3m = runPlaybook("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS, sc2_3wg);
        int best6m = runPlaybook("playbooks/cal-gas-dual-6w.yaml", Race.PROTOSS, sc2_6wg);
        System.out.printf("  3w: vespene EM=%d SC2=%d gap=%+.1f%%  minerals EM=%d SC2=%d gap=%+.1f%%%n",
                          best3v, s3v, (best3v - s3v) * 100.0 / s3v, best3m, s3m, (best3m - s3m) * 100.0 / s3m);
        System.out.printf("  6w: vespene EM=%d SC2=%d gap=%+.1f%%  minerals EM=%d SC2=%d gap=%+.1f%%%n",
                          best6v, s6v, (best6v - s6v) * 100.0 / s6v, best6m, s6m, (best6m - s6m) * 100.0 / s6m);
    }

    private CalibrationBaseline loadBaseline(String resource) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {throw new IOException("Resource not found: " + resource);}
            return CalibrationBaseline.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
