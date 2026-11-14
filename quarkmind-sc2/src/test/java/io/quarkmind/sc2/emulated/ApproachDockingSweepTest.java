package io.quarkmind.sc2.emulated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import io.quarkmind.sc2.playbook.SC2DeliveryHandler;
import io.quarkmind.sc2.playbook.SC2PlaybookRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Refs #578 — sweep phase durations and stagger to find values
 * that produce exactly 3460 minerals at tick 304 in the 12w dual-run.
 */
@Tag("diagnostic")
class ApproachDockingSweepTest {

    @AfterEach
    void resetDefaults() {
        SimulatedWorker.resetToDefaults();
        GasSlot.ENTRY_LOCK_DURATION = 0;
    }

    @Test
    void sweepApproachDocking12w() {
        CalibrationBaseline baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        int sc2Final = baseline.snapshots().get(304).minerals();

        System.out.printf("=== Approach-Docking Sweep (12w Protoss, SC2 target: %d) ===%n%n", sc2Final);
        System.out.printf("%-10s | %-10s | %-8s | %-10s%n", "approach", "EM minerals", "gap", "deliveries");
        System.out.println("-".repeat(50));

        for (double approach = 6.5; approach <= 9.0; approach += 0.1) {
            SimulatedWorker.APPROACH_DOCKING_LOOPS = approach;
            int emMinerals = run304Ticks(baseline);
            int gap = emMinerals - sc2Final;
            int deliveries = (emMinerals - 50) / 5;
            System.out.printf("%-10.1f | %-10d | %+8d | %-10d%n", approach, emMinerals, gap, deliveries);
        }
    }

    @Test
    void sweepStagger12w() {
        CalibrationBaseline baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        int sc2Final = baseline.snapshots().get(304).minerals();

        System.out.printf("=== Stagger Sweep (12w, SC2 target: %d) ===%n%n", sc2Final);
        System.out.printf("%-10s | %-10s | %-8s%n", "stagger", "EM minerals", "gap");
        System.out.println("-".repeat(35));

        for (double stagger = 3.0; stagger <= 15.0; stagger += 0.5) {
            SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS = stagger;
            int emMinerals = run304Ticks(baseline);
            int gap = emMinerals - sc2Final;
            System.out.printf("%-10.1f | %-10d | %+8d%n", stagger, emMinerals, gap);
        }
    }

    @Test
    void sweepStaggerFine12w() {
        CalibrationBaseline baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        int sc2Final = baseline.snapshots().get(304).minerals();

        System.out.printf("=== Stagger Fine Sweep (12w, SC2 target: %d) ===%n%n", sc2Final);
        System.out.printf("%-10s | %-10s | %-8s%n", "stagger", "EM minerals", "gap");
        System.out.println("-".repeat(35));

        for (double stagger = 5.0; stagger <= 8.0; stagger += 0.1) {
            SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS = stagger;
            int emMinerals = run304Ticks(baseline);
            int gap = emMinerals - sc2Final;
            System.out.printf("%-10.1f | %-10d | %+8d%n", stagger, emMinerals, gap);
        }
    }

    @Test
    void sweep2DApproachDeposit12w() {
        CalibrationBaseline baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        int sc2Final = baseline.snapshots().get(304).minerals();

        System.out.printf("=== 2D Sweep: approach x deposit (12w, SC2 target: %d) ===%n%n", sc2Final);
        System.out.printf("%-8s %-8s | %-10s | %-8s%n", "approach", "deposit", "EM minerals", "gap");
        System.out.println("-".repeat(45));

        for (double approach = 6.0; approach <= 8.0; approach += 0.5) {
            for (double deposit = 2.0; deposit <= 5.0; deposit += 0.5) {
                SimulatedWorker.APPROACH_DOCKING_LOOPS = approach;
                SimulatedWorker.DEPOSIT_DURATION_LOOPS = deposit;
                int emMinerals = run304Ticks(baseline);
                int gap = emMinerals - sc2Final;
                System.out.printf("%-8.1f %-8.1f | %-10d | %+8d%n", approach, deposit, emMinerals, gap);
            }
        }
    }

    @Test
    void sweepDockingOverhead() {
        CalibrationBaseline bl12w = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        CalibrationBaseline bl16w = loadBaseline("calibration/cal-mineral-saturated-16w.json");
        int sc2_12w = bl12w.snapshots().get(304).minerals();
        int sc2_16w = bl16w.snapshots().get(304).minerals();

        System.out.printf("=== Patch Docking Overhead Sweep (SC2: 12w=%d, 16w=%d) ===%n%n", sc2_12w, sc2_16w);
        System.out.printf("%-10s %-10s | %-10s %-10s | %-10s %-10s%n",
            "docking", "stagger", "12w EM", "12w gap", "16w EM", "16w gap");
        System.out.println("-".repeat(65));

        for (double docking = 35; docking <= 37; docking += 1) {
            for (double stagger = 6.0; stagger <= 9.0; stagger += 0.1) {
                SimulatedWorker.PATCH_DOCKING_OVERHEAD_LOOPS = docking;
                SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS = stagger;
                int em12w = run304Ticks(bl12w);
                int gap12w = em12w - sc2_12w;
                if (Math.abs(gap12w) > 5) continue;
                int[] r16w = runPlaybook("playbooks/cal-mineral-saturated-16w.yaml", bl16w);
                int gap16w = r16w[0] - sc2_16w;
                System.out.printf("%-10.0f %-10.1f | %-10d %+10d | %-10d %+10d%n",
                    docking, stagger, em12w, gap12w, r16w[0], gap16w);
            }
        }
    }

    @Test
    void sweepGasEntryLock() {
        CalibrationBaseline bl3w = loadBaseline("calibration/cal-gas-baseline-3w.json");
        CalibrationBaseline bl6w = loadBaseline("calibration/cal-gas-dual-6w.json");
        int sc2Ves3w = bl3w.snapshots().get(304).vespene();
        int sc2Ves6w = bl6w.snapshots().get(304).vespene();

        System.out.printf("=== 2D Sweep: entry lock x gas harvest (SC2: 3w ves=%d, 6w ves=%d) ===%n%n", sc2Ves3w, sc2Ves6w);
        System.out.printf("%-8s %-8s | %-8s %-8s %-8s | %-8s %-8s %-8s%n",
            "lock", "harvest", "3w ves", "3w gap", "3w pct", "6w ves", "6w gap", "6w pct");
        System.out.println("-".repeat(75));

        for (double lock = 0; lock <= 20; lock += 2) {
            for (double harvest = 14; harvest <= 28; harvest += 2) {
                SimulatedWorker.resetToDefaults();
                GasSlot.ENTRY_LOCK_DURATION = lock;
                SimulatedWorker.GAS_HARVEST_DURATION_LOOPS = harvest;

                int[] r3w = runPlaybook("playbooks/cal-gas-baseline-3w.yaml", bl3w);
                int[] r6w = runPlaybook("playbooks/cal-gas-dual-6w.yaml", bl6w);

                int gap3w = r3w[1] - sc2Ves3w;
                int gap6w = r6w[1] - sc2Ves6w;
                double pct3w = 100.0 * gap3w / Math.max(1, sc2Ves3w);
                double pct6w = 100.0 * gap6w / Math.max(1, sc2Ves6w);

                System.out.printf("%-8.0f %-8.0f | %-8d %+8d %+7.1f%% | %-8d %+8d %+7.1f%%%n",
                    lock, harvest, r3w[1], gap3w, pct3w, r6w[1], gap6w, pct6w);
            }
        }
    }

    private int run304Ticks(CalibrationBaseline baseline) {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        if (baseline.basePosition() != null && baseline.mineralPatches() != null) {
            float fx = 8f - baseline.basePosition()[0];
            float fy = 8f - baseline.basePosition()[1];
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                .map(p -> new Resource(
                    "cal-" + baseline.mineralPatches().indexOf(p),
                    new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                .toList());
        }
        game.reset();

        for (int tick = 0; tick < 304; tick++) {
            game.tick();
        }
        return game.snapshot().minerals();
    }

    @Test
    void sweepGasHarvestDuration() {
        CalibrationBaseline bl3w = loadBaseline("calibration/cal-gas-baseline-3w.json");
        CalibrationBaseline bl6w = loadBaseline("calibration/cal-gas-dual-6w.json");
        int sc2Min3w = bl3w.snapshots().get(304).minerals();
        int sc2Ves3w = bl3w.snapshots().get(304).vespene();
        int sc2Min6w = bl6w.snapshots().get(304).minerals();

        System.out.printf("=== Gas Harvest Duration Sweep ===%n");
        System.out.printf("SC2 targets — 3w: min=%d ves=%d, 6w: min=%d ves=%d%n%n", sc2Min3w, sc2Ves3w, sc2Min6w, sc2Ves6w);
        System.out.printf("%-8s | %-8s %-8s %-8s %-8s | %-8s %-8s %-8s %-8s%n",
            "harvest", "3w min", "3w ves", "minGap", "vesGap", "6w min", "6w ves", "minGap", "vesGap");
        System.out.println("-".repeat(90));

        for (double harvest = 18.0; harvest <= 30.0; harvest += 1.0) {
            SimulatedWorker.resetToDefaults();
            SimulatedWorker.GAS_HARVEST_DURATION_LOOPS = harvest;

            int[] r3w = runPlaybook("playbooks/cal-gas-baseline-3w.yaml", bl3w);
            int[] r6w = runPlaybook("playbooks/cal-gas-dual-6w.yaml", bl6w);

            System.out.printf("%-8.0f | %-8d %-8d %+8d %+8d | %-8d %-8d %+8d %+8d%n",
                harvest,
                r3w[0], r3w[1], r3w[0] - sc2Min3w, r3w[1] - sc2Ves3w,
                r6w[0], r6w[1], r6w[0] - sc2Min6w, r6w[1] - sc2Ves6w);
        }
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private int[] runPlaybook(String playbookResource, CalibrationBaseline baseline) {
        String yaml = SC2PlaybookRunner.loadResource(playbookResource);
        JsonNode root;
        try { root = YAML.readTree(yaml); } catch (Exception e) {
            throw new RuntimeException("Failed to parse playbook", e);
        }

        Race race = SC2PlaybookRunner.resolveRace(root);
        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        float offX = 0, offY = 0;
        if (baseline.basePosition() != null && baseline.mineralPatches() != null) {
            offX = 8f - baseline.basePosition()[0];
            offY = 8f - baseline.basePosition()[1];
            float fx = offX, fy = offY;
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                .map(p -> new Resource("cal-" + baseline.mineralPatches().indexOf(p),
                    new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                .toList());
        }
        game.reset();
        if (baseline.basePosition() != null && baseline.geyserPositions() != null) {
            float fx = offX, fy = offY;
            Point2d emBase = new Point2d(8, 8);
            game.setGeyserResources(baseline.geyserPositions().stream()
                .map(g -> new Resource("cal-geyser-" + baseline.geyserPositions().indexOf(g),
                    new Point2d(g.x() + fx, g.y() + fy), g.amount()))
                .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                .toList());
        }
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        boolean[] fired = new boolean[steps.size()];
        int gasIdx = 0;
        int tickLimit = Math.min(baseline.tickCount(), baseline.snapshots().size());

        for (int tick = 0; tick < tickLimit; tick++) {
            game.tick();
            game.processDeferredResearchIntents();
            int supplyUsed = (int) game.snapshot().supplyUsed();

            int reservedMinerals = 0;
            int reservedStepIndex = -1;
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                if ("build".equals(step.action()) && step.supplyTrigger() > 0
                    && supplyUsed >= step.supplyTrigger() && !fired[i]) {
                    BuildingType bt = BuildingType.valueOf((String) step.params().get("build"));
                    int cost = SC2Data.mineralCost(bt);
                    if (cost >= 75) {
                        reservedMinerals = cost;
                        reservedStepIndex = i;
                        break;
                    }
                }
            }

            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                boolean canFire = step.shouldFire(tick, supplyUsed) && (!fired[i] || step.loop());
                if (canFire && reservedMinerals > 0
                    && game.snapshot().minerals() < reservedMinerals
                    && i != reservedStepIndex) {
                    if (step.loop() && "train".equals(step.action())) continue;
                    if ("build".equals(step.action()) && step.supplyTrigger() > 0 && !fired[i]) continue;
                }
                if (canFire) {
                    if ("assert".equals(step.action())) {
                        fired[i] = true;
                    } else if ("build".equals(step.action())) {
                        BuildingType bt = BuildingType.valueOf((String) step.params().get("build"));
                        Point2d pos;
                        if (SC2Data.isGasBuilding(bt)) {
                            var geysers = game.snapshot().geysers();
                            pos = (geysers != null && gasIdx < geysers.size())
                                ? geysers.get(gasIdx++).position()
                                : new Point2d(16, 12);
                        } else {
                            pos = new Point2d(30, 30);
                        }
                        game.debugCreateBuilding(bt, pos);
                        fired[i] = true;
                    } else {
                        Map<String, Object> data = new HashMap<>(step.params());
                        data.put("action", step.action());
                        var outcome = handler.execute(step.action() + "-" + tick, data, null);
                        if (step.supplyTrigger() > 0) {
                            if (outcome.success()) fired[i] = true;
                        } else {
                            fired[i] = true;
                        }
                    }
                }
            }
        }
        return new int[]{ game.snapshot().minerals(), game.snapshot().vespene() };
    }

    private static CalibrationBaseline loadBaseline(String resource) {
        try (InputStream is = ApproachDockingSweepTest.class.getClassLoader().getResourceAsStream(resource)) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new ObjectMapper().readValue(json, CalibrationBaseline.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline: " + resource, e);
        }
    }
}
