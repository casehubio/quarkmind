package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.WorkerAction;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("diagnostic")
class ExpansionTransferDiagnosticTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Point2d MAIN_POS = new Point2d(8, 8);
    private static final Point2d EXP_POS = new Point2d(30, 30);

    private int[] workersByProximity(List<WorkerSnapshot> workers) {
        int main = 0, exp = 0;
        for (WorkerSnapshot w : workers) {
            if (w.action() == WorkerAction.OTHER) continue;
            double dMain = w.position().distanceTo(MAIN_POS);
            double dExp = w.position().distanceTo(EXP_POS);
            if (dExp < dMain) exp++;
            else main++;
        }
        return new int[]{main, exp};
    }

    @Test
    void transferMechanismIsolation() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.reset();

        System.out.println("[Transfer] Isolation test — create expansion and observe transfer");

        for (int tick = 0; tick < 20; tick++) game.tick();

        var preSnap = game.snapshot();
        System.out.printf("  Before expansion: probes=%d, minerals=%d%n",
                (int) preSnap.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count(),
                preSnap.minerals());

        game.debugCreateBuilding(BuildingType.NEXUS, EXP_POS);

        List<WorkerSnapshot> ws = game.workerSnapshots();
        long transferring = ws.stream().filter(w -> w.action() == WorkerAction.OTHER).count();
        System.out.printf("  After expansion: transferring=%d%n", transferring);
        assertEquals(4, transferring, "Should transfer MAX_TRANSFER_PER_EXPANSION=4 workers");

        int firstArrivalTick = -1;
        int allArrivedTick = -1;

        System.out.printf("%-8s %6s %6s %6s %6s %8s%n", "tick", "main", "exp", "walk", "total", "minerals");
        for (int tick = 1; tick <= 15; tick++) {
            game.tick();
            List<WorkerSnapshot> wSnap = game.workerSnapshots();
            long walking = wSnap.stream().filter(w -> w.action() == WorkerAction.OTHER).count();
            int[] dist = workersByProximity(wSnap);
            int total = (int) wSnap.stream().filter(w -> w.action() != WorkerAction.OTHER).count();
            System.out.printf("+%-7d %6d %6d %6d %6d %8d%n", tick, dist[0], dist[1], walking, total, game.snapshot().minerals());

            if (firstArrivalTick < 0 && dist[1] > 0) firstArrivalTick = tick;
            if (allArrivedTick < 0 && walking == 0 && dist[1] > 0) allArrivedTick = tick;
        }

        System.out.println();
        System.out.printf("  First worker at expansion: tick +%d%n", firstArrivalTick);
        System.out.printf("  All workers arrived:       tick +%d%n", allArrivedTick);
        double distance = MAIN_POS.distanceTo(EXP_POS);
        System.out.printf("  Walk distance:             %.1f units%n", distance);
        System.out.printf("  Transfer delay:            %d ticks (%.1f seconds)%n",
                allArrivedTick, allArrivedTick * 22.0 / 22.4);

        List<WorkerSnapshot> finalWs = game.workerSnapshots();
        int[] finalDist = workersByProximity(finalWs);
        assertTrue(finalDist[1] > 0, "Workers should be at expansion");
        assertEquals(4, finalDist[1], "4 workers should have transferred to expansion");
    }

    @Test
    void expansionTransferAnalysis() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-expansion-transfer.json");
        Race race = Race.PROTOSS;
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        game.reset();
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        String yaml = SC2PlaybookRunner.loadResource("playbooks/cal-expansion-transfer.yaml");
        JsonNode root;
        try { root = YAML.readTree(yaml); } catch (Exception e) { throw new RuntimeException(e); }
        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);
        boolean[] fired = new boolean[steps.size()];

        int tickLimit = Math.min(sc2Baseline.tickCount(), sc2Baseline.snapshots().size());
        int expansionTick = -1;
        int firstExpWorkerTick = -1;

        System.out.println("[ExpTransfer] Expansion transfer diagnostic — EM vs SC2 baseline");
        System.out.println();
        System.out.printf("%-5s %8s %8s %6s %6s %6s %6s %6s %s%n",
                "tick", "SC2_min", "EM_min", "delta", "probes", "main", "exp", "walk", "notes");
        System.out.println("-".repeat(80));

        for (int tick = 0; tick < tickLimit; tick++) {
            CalibrationSnapshot sc2Snap = sc2Baseline.snapshots().get(tick);
            var emSnap = game.snapshot();
            int emMinerals = emSnap.minerals();
            int sc2Minerals = sc2Snap.minerals();
            int emProbes = (int) emSnap.myUnits().stream()
                    .filter(u -> u.type() == UnitType.PROBE).count();

            List<WorkerSnapshot> workerSnaps = game.workerSnapshots();
            long walking = workerSnaps.stream()
                    .filter(w -> w.action() == WorkerAction.OTHER).count();
            int[] dist = workersByProximity(workerSnaps);

            StringBuilder notes = new StringBuilder();
            if (expansionTick < 0 && emSnap.myBuildings().stream()
                    .filter(b -> b.type() == BuildingType.NEXUS).count() > 1) {
                expansionTick = tick;
                notes.append("NEXUS_2 ");
            }
            if (firstExpWorkerTick < 0 && dist[1] > 0) {
                firstExpWorkerTick = tick;
                notes.append("FIRST_EXP_WORKER ");
            }

            boolean show = (tick >= 80 && tick <= 110)
                    || tick % 50 == 0
                    || !notes.isEmpty();
            if (show) {
                System.out.printf("%-5d %8d %8d %+6d %6d %6d %6d %6d %s%n",
                        tick, sc2Minerals, emMinerals,
                        emMinerals - sc2Minerals, emProbes,
                        dist[0], dist[1], walking, notes);
            }

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
                    if (cost >= SC2PlaybookRunner.RESERVATION_THRESHOLD) {
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
                    if (step.loop() && "train".equals(step.action())) { continue; }
                    if ("build".equals(step.action()) && step.supplyTrigger() > 0 && !fired[i]) { continue; }
                }
                if (canFire) {
                    if ("build".equals(step.action())) {
                        BuildingType bt = BuildingType.valueOf((String) step.params().get("build"));
                        Point2d pos;
                        if (SC2Data.isGasBuilding(bt)) {
                            pos = new Point2d(16, 12);
                        } else if (bt == BuildingType.NEXUS) {
                            pos = EXP_POS;
                        } else {
                            pos = new Point2d(14, 14);
                        }
                        game.debugCreateBuilding(bt, pos);
                        fired[i] = true;
                    } else {
                        Map<String, Object> data = new HashMap<>(step.params());
                        data.put("action", step.action());
                        var outcome = handler.execute(step.action() + "-" + tick, data, null);
                        if (step.supplyTrigger() > 0) {
                            if (outcome.success()) { fired[i] = true; }
                        } else {
                            fired[i] = true;
                        }
                    }
                }
            }
        }

        System.out.println();
        System.out.println("[ExpTransfer] Summary:");
        System.out.printf("  Expansion Nexus appeared:  tick %d%n", expansionTick);
        System.out.printf("  First expansion worker:    tick %d%n", firstExpWorkerTick);
        if (expansionTick >= 0 && firstExpWorkerTick >= 0) {
            int delay = firstExpWorkerTick - expansionTick;
            System.out.printf("  Transfer delay:            %d ticks (%.1f seconds)%n",
                    delay, delay * 22.0 / 22.4);
        }

        double distance = MAIN_POS.distanceTo(EXP_POS);
        System.out.printf("  Walk distance (main->exp): %.1f units%n", distance);

        List<WorkerSnapshot> finalWs = game.workerSnapshots();
        int[] finalDist = workersByProximity(finalWs);
        int finalProbes = (int) game.snapshot().myUnits().stream()
                .filter(u -> u.type() == UnitType.PROBE).count();
        System.out.printf("  Final workers: main=%d, exp=%d (total probes=%d)%n",
                finalDist[0], finalDist[1], finalProbes);

        int sc2FinalMinerals = sc2Baseline.snapshots().get(tickLimit - 1).minerals();
        int emFinalMinerals = game.snapshot().minerals();
        System.out.printf("  Final minerals: SC2=%d, EM=%d, delta=%+d (%.1f%%)%n",
                sc2FinalMinerals, emFinalMinerals,
                emFinalMinerals - sc2FinalMinerals,
                sc2FinalMinerals > 0 ? 100.0 * (emFinalMinerals - sc2FinalMinerals) / sc2FinalMinerals : 0);
    }

    private static CalibrationBaseline loadBaseline(String resourcePath) {
        try (InputStream is = ExpansionTransferDiagnosticTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Baseline not found: " + resourcePath);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return CalibrationBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline: " + resourcePath, e);
        }
    }
}
