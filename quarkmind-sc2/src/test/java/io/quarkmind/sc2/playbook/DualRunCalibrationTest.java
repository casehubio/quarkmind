package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualRunCalibrationTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void runsAgainstFixtureWithoutError() {
        CalibrationBaseline baseline = loadBaseline("calibration/protoss-probe-economy-fixture.json");
        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        System.out.println(result.report());
        assertNotNull(result);
    }

    @Test
    void dualRunCalMineralBaseline12wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-12w.yaml", sc2Baseline);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @org.junit.jupiter.api.Tag("diagnostic")
    @Test
    void diagnosticEarlyTickMineralTrace() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        Race                race        = Race.PROTOSS;
        EmulatedGame        game        = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        if (sc2Baseline.basePosition() != null && sc2Baseline.mineralPatches() != null) {
            float fx = 8f - sc2Baseline.basePosition()[0];
            float fy = 8f - sc2Baseline.basePosition()[1];
            game.setMineralPatchResources(sc2Baseline.mineralPatches().stream()
                                                     .map(p -> new io.quarkmind.domain.Resource(
                                                             "cal-" + sc2Baseline.mineralPatches().indexOf(p),
                                                             new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                                                     .toList());
        }
        game.reset();

        System.out.println("=== EM per-tick mineral trace (real patch positions) ===");
        System.out.println("Tick | EM minerals | SC2 minerals | Delta | EM delta");
        int prevEm = 50;
        for (int tick = 0; tick <= 20; tick++) {
            int emMinerals  = game.snapshot().minerals();
            int sc2Minerals = sc2Baseline.snapshots().get(tick).minerals();
            int delta       = emMinerals - sc2Minerals;
            int emDelta     = emMinerals - prevEm;
            System.out.printf("  %3d |  %10d |   %10d |  %+4d | %+4d%n",
                              tick, emMinerals, sc2Minerals, delta, emDelta);
            prevEm = emMinerals;
            game.tick();
        }
    }


    @Test
    void earlyTickMineralDeliverySpread() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        if (sc2Baseline.basePosition() != null && sc2Baseline.mineralPatches() != null) {
            float fx = 8f - sc2Baseline.basePosition()[0];
            float fy = 8f - sc2Baseline.basePosition()[1];
            game.setMineralPatchResources(sc2Baseline.mineralPatches().stream()
                    .map(p -> new io.quarkmind.domain.Resource(
                            "cal-" + sc2Baseline.mineralPatches().indexOf(p),
                            new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                    .toList());
        }
        game.reset();

        for (int t = 0; t < 6; t++) game.tick();
        int emTick6 = game.snapshot().minerals();
        int sc2Tick6 = sc2Baseline.snapshots().get(6).minerals();
        assertTrue(Math.abs(emTick6 - sc2Tick6) <= 10,
                "Tick 6 delta " + (emTick6 - sc2Tick6) + " exceeds ±10");

        for (int t = 6; t < 20; t++) game.tick();
        int emTick20 = game.snapshot().minerals();
        int sc2Tick20 = sc2Baseline.snapshots().get(20).minerals();
        assertTrue(Math.abs(emTick20 - sc2Tick20) <= 5,
                "Tick 20 delta " + (emTick20 - sc2Tick20) + " exceeds ±5");
    }

    @Test
    void dualRunCalMineralBaselineTerranAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-terran.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-terran.yaml", sc2Baseline);
        System.out.println(result.report());
    }

    @Test
    void dualRunCalMineralBaselineZergAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-zerg.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-baseline-zerg.yaml", sc2Baseline);
        System.out.println(result.report());
    }

    @Test
    void dualRunCalMineralSaturated16wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-saturated-16w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-saturated-16w.yaml", sc2Baseline, true, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalGasBaseline3wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-gas-baseline-3w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-gas-baseline-3w.yaml", sc2Baseline, true, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalGasDual6wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-gas-dual-6w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-gas-dual-6w.yaml", sc2Baseline, true, 3);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
        // Print gas rate comparison at checkpoints
        var snapshots = sc2Baseline.snapshots();
        for (int cp : new int[]{60, 120, 180, 240, 304}) {
            if (cp < snapshots.size()) {
                var s = snapshots.get(cp);
                System.out.printf("[Gas] tick %d: SC2 vespene=%d%n", cp, s.vespene());
            }
        }
    }


    @Test
    void dualRunCalMineralSaturated24wAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-saturated-24w.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mineral-saturated-24w.yaml", sc2Baseline, true, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void dualRunCalWorkerSpeedSingleAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-worker-speed-single.json");
        CalibrationResult   result      = calibrate("playbooks/cal-worker-speed-single.yaml", sc2Baseline);
        System.out.println(result.report());
    }

    @Test
    void dualRunCalMuleIsolationAgainstSC2() {
        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mule-isolation.json");
        CalibrationResult   result      = calibrate("playbooks/cal-mule-isolation.yaml", sc2Baseline, true, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[DualRun] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
    }

    @Test
    void earlyExitOnUnitMismatch() {
        CalibrationBaseline baseline = new CalibrationBaseline(
            "test", "PROTOSS", "fixture", "2026-10-30T00:00:00Z", 3,
            List.of(
                new CalibrationSnapshot(0, 50, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(1, 62, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(2, 74, 0, 15, 12, Map.of("PROBE", 99), Map.of("NEXUS", 1), Set.of())
            ),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertFalse(result.passed());
        assertEquals(2, result.divergenceTick());
        assertEquals("units.PROBE", result.divergenceField());
        assertEquals("99", result.expected());
        System.out.println(result.report());
    }

    @Test
    void earlyExitOnBuildingMismatch() {
        CalibrationBaseline baseline = new CalibrationBaseline(
            "test", "PROTOSS", "fixture", "2026-10-30T00:00:00Z", 2,
            List.of(
                new CalibrationSnapshot(0, 50, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(1, 62, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1, "GATEWAY", 3), Set.of())
            ),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertFalse(result.passed());
        assertEquals(1, result.divergenceTick());
        assertEquals("buildings.GATEWAY", result.divergenceField());
        assertEquals("3", result.expected());
        assertEquals("0", result.actual());
    }

    @Test
    void earlyExitOnUpgradeMismatch() {
        CalibrationBaseline baseline = new CalibrationBaseline(
            "test", "PROTOSS", "fixture", "2026-10-30T00:00:00Z", 2,
            List.of(
                new CalibrationSnapshot(0, 50, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(1, 62, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of("WarpGateResearch"))
            ),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertFalse(result.passed());
        assertEquals(1, result.divergenceTick());
        assertTrue(result.divergenceField().startsWith("upgrades"));
    }

    @Test
    void passesWhenNoStructuralDivergence() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.reset();

        CalibrationSnapshot tick0 = CalibrationSnapshot.from(0, game.snapshot());
        game.tick();
        CalibrationSnapshot tick1 = CalibrationSnapshot.from(1, game.snapshot());

        CalibrationBaseline baseline = new CalibrationBaseline(
            "self-test", "PROTOSS", "emulated", "2026-10-30T00:00:00Z", 2,
            List.of(tick0, tick1),
            null, null, null
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertTrue(result.passed(), "Self-baseline should always pass: " + result.report());
    }

    @Test
    void selfBaselineChronoBoostEconomy() {
        assertSelfBaseline("playbooks/chrono-boost-economy.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineMuleIncome() {
        assertSelfBaseline("playbooks/mule-income.yaml", Race.TERRAN);
    }

    @Test
    void selfBaselineInjectLarva() {
        assertSelfBaseline("playbooks/inject-larva.yaml", Race.ZERG);
    }

    @Test
    void selfBaselineGasSaturation() {
        assertSelfBaseline("playbooks/gas-saturation.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineUpgradeResearch() {
        assertSelfBaseline("playbooks/upgrade-research.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineTwoBaseProduction() {
        assertSelfBaseline("playbooks/two-base-production.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralBaseline12w() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-12w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralSaturated16w() {
        assertSelfBaseline("playbooks/cal-mineral-saturated-16w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralSaturated24w() {
        assertSelfBaseline("playbooks/cal-mineral-saturated-24w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalGasBaseline3w() {
        assertSelfBaseline("playbooks/cal-gas-baseline-3w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalGasDual6w() {
        assertSelfBaseline("playbooks/cal-gas-dual-6w.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalMineralBaselineTerran() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-terran.yaml", Race.TERRAN);
    }

    @Test
    void selfBaselineCalMuleIsolation() {
        assertSelfBaseline("playbooks/cal-mule-isolation.yaml", Race.TERRAN);
    }

    @Test
    void selfBaselineCalMineralBaselineZerg() {
        assertSelfBaseline("playbooks/cal-mineral-baseline-zerg.yaml", Race.ZERG);
    }

    @Test
    void selfBaselineCalExpansionTransfer() {
        assertSelfBaseline("playbooks/cal-expansion-transfer.yaml", Race.PROTOSS);
    }

    @Test
    void selfBaselineCalWorkerSpeedSingle() {
        assertSelfBaseline("playbooks/cal-worker-speed-single.yaml", Race.PROTOSS);
    }


    private static void assertSelfBaseline(String playbookResource, Race race) {
        String   yaml = SC2PlaybookRunner.loadResource(playbookResource);
        JsonNode root;
        try {root = YAML.readTree(yaml);} catch (Exception e) {
            throw new RuntimeException("Failed to parse playbook", e);
        }

        List<SC2PlaybookRunner.PlaybookStep> steps         = SC2PlaybookRunner.parseSteps(root);
        int                                  durationTicks = SC2PlaybookRunner.resolveDuration(root, steps);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        game.reset();
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        List<CalibrationSnapshot> snapshots = new java.util.ArrayList<>();
        boolean[]                 fired     = new boolean[steps.size()];

        for (int tick = 0; tick < durationTicks; tick++) {
            snapshots.add(CalibrationSnapshot.from(tick, game.snapshot()));
            game.tick();
            game.processDeferredResearchIntents();

            int supplyUsed = (int) game.snapshot().supplyUsed();

            int reservedMinerals  = 0;
            int reservedStepIndex = -1;
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                if ("build".equals(step.action()) && step.supplyTrigger() > 0
                    && supplyUsed >= step.supplyTrigger() && !fired[i]) {
                    BuildingType bt   = BuildingType.valueOf((String) step.params().get("build"));
                    int          cost = SC2Data.mineralCost(bt);
                    if (cost >= SC2PlaybookRunner.RESERVATION_THRESHOLD) {
                        reservedMinerals  = cost;
                        reservedStepIndex = i;
                        break;
                    }
                }
            }

            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step    = steps.get(i);
                boolean                        canFire = step.shouldFire(tick, supplyUsed) && (!fired[i] || step.loop());
                if (canFire && reservedMinerals > 0
                    && game.snapshot().minerals() < reservedMinerals
                    && i != reservedStepIndex) {
                    if (step.loop() && "train".equals(step.action())) {continue;}
                    if ("build".equals(step.action()) && step.supplyTrigger() > 0 && !fired[i]) {continue;}
                }
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

        CalibrationBaseline baseline = new CalibrationBaseline(
                playbookResource, race.name(), "emulated-self", java.time.Instant.now().toString(),
                durationTicks, snapshots, null, null, null);

        CalibrationResult result = calibrate(playbookResource, baseline);
        assertTrue(result.passed(), "Self-baseline should always pass: " + result.report());
    }

    static CalibrationResult calibrate(String playbookResource, CalibrationBaseline baseline) {
        return calibrate(playbookResource, baseline, false);
    }

    static CalibrationResult calibrate(String playbookResource, CalibrationBaseline baseline,
                                       boolean skipBuildings) {
        return calibrate(playbookResource, baseline, skipBuildings, 0);
    }

    static CalibrationResult calibrate(String playbookResource, CalibrationBaseline baseline,
                                       boolean skipBuildings, int unitTolerance) {
        String   yaml = SC2PlaybookRunner.loadResource(playbookResource);
        JsonNode root;
        try {root = YAML.readTree(yaml);} catch (Exception e) {
            throw new RuntimeException("Failed to parse playbook", e);
        }

        Race                                 race  = SC2PlaybookRunner.resolveRace(root);
        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        float offX = 0, offY = 0;
        if (baseline.basePosition() != null && baseline.mineralPatches() != null) {
            offX = 8f - baseline.basePosition()[0];
            offY = 8f - baseline.basePosition()[1];
            float fx = offX, fy = offY;
            game.setMineralPatchResources(baseline.mineralPatches().stream()
                .map(p -> new io.quarkmind.domain.Resource(
                    "cal-" + baseline.mineralPatches().indexOf(p),
                    new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                .toList());
        }
        game.reset();
        if (baseline.basePosition() != null && baseline.geyserPositions() != null) {
            float fx = offX, fy = offY;
            Point2d emBase = new Point2d(8, 8);
            game.setGeyserResources(baseline.geyserPositions().stream()
                .map(g -> new io.quarkmind.domain.Resource(
                    "cal-geyser-" + baseline.geyserPositions().indexOf(g),
                    new Point2d(g.x() + fx, g.y() + fy), g.amount()))
                .filter(g -> g.position().distanceTo(emBase) <= 12.0)
                .toList());
        }
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        boolean[] fired     = new boolean[steps.size()];
        int       gasIdx    = 0;
        int       tickLimit = Math.min(baseline.tickCount(), baseline.snapshots().size());

        for (int tick = 0; tick < tickLimit; tick++) {
            CalibrationSnapshot expected = baseline.snapshots().get(tick);
            CalibrationSnapshot actual   = CalibrationSnapshot.from(tick, game.snapshot());

            CalibrationResult divergence = compare(expected, actual, skipBuildings, unitTolerance);
            if (divergence != null) {return divergence;}

            if (expected.minerals() != actual.minerals() || expected.vespene() != actual.vespene()) {
                System.out.printf("  [econ] tick %d: minerals %d vs %d, vespene %d vs %d%n",
                                  tick, expected.minerals(), actual.minerals(),
                                  expected.vespene(), actual.vespene());
            }

            game.tick();
            game.processDeferredResearchIntents();

            int supplyUsed = (int) game.snapshot().supplyUsed();

            int reservedMinerals  = 0;
            int reservedStepIndex = -1;
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                if ("build".equals(step.action()) && step.supplyTrigger() > 0
                    && supplyUsed >= step.supplyTrigger() && !fired[i]) {
                    BuildingType bt   = BuildingType.valueOf((String) step.params().get("build"));
                    int          cost = SC2Data.mineralCost(bt);
                    if (cost >= SC2PlaybookRunner.RESERVATION_THRESHOLD) {
                        reservedMinerals  = cost;
                        reservedStepIndex = i;
                        break;
                    }
                }
            }

            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                boolean canFire = step.shouldFire(tick, supplyUsed)
                                  && (!fired[i] || step.loop());
                if (canFire && reservedMinerals > 0
                    && game.snapshot().minerals() < reservedMinerals
                    && i != reservedStepIndex) {
                    if (step.loop() && "train".equals(step.action())) {continue;}
                    if ("build".equals(step.action()) && step.supplyTrigger() > 0 && !fired[i]) {continue;}
                }
                if (canFire) {
                    if (skipBuildings && "build".equals(step.action())) {
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
                            if (outcome.success()) {fired[i] = true;}
                        } else {
                            fired[i] = true;
                        }
                    }
                }
            }
        }

        return CalibrationResult.passed(tickLimit);
    }

    private static CalibrationResult compare(CalibrationSnapshot expected, CalibrationSnapshot actual) {
        return compare(expected, actual, false, 0);
    }

    private static CalibrationResult compare(CalibrationSnapshot expected, CalibrationSnapshot actual,
                                             boolean skipBuildings, int unitTolerance) {
        for (Map.Entry<String, Integer> entry : expected.units().entrySet()) {
            int actualCount = actual.units().getOrDefault(entry.getKey(), 0);
            if (Math.abs(actualCount - entry.getValue()) > unitTolerance) {
                return CalibrationResult.diverged(expected.tick(),
                    "units." + entry.getKey(),
                    String.valueOf(entry.getValue()),
                    String.valueOf(actualCount));
            }
        }
        for (Map.Entry<String, Integer> entry : actual.units().entrySet()) {
            if (!expected.units().containsKey(entry.getKey()) && entry.getValue() > unitTolerance) {
                return CalibrationResult.diverged(expected.tick(),
                    "units." + entry.getKey(),
                    "0",
                    String.valueOf(entry.getValue()));
            }
        }

        if (!skipBuildings) {
            for (Map.Entry<String, Integer> entry : expected.buildings().entrySet()) {
                int actualCount = actual.buildings().getOrDefault(entry.getKey(), 0);
                if (actualCount != entry.getValue()) {
                    return CalibrationResult.diverged(expected.tick(),
                        "buildings." + entry.getKey(),
                        String.valueOf(entry.getValue()),
                        String.valueOf(actualCount));
                }
            }
            for (Map.Entry<String, Integer> entry : actual.buildings().entrySet()) {
                if (!expected.buildings().containsKey(entry.getKey()) && entry.getValue() > 0) {
                    return CalibrationResult.diverged(expected.tick(),
                        "buildings." + entry.getKey(),
                        "0",
                        String.valueOf(entry.getValue()));
                }
            }
        }

        if (!expected.upgrades().equals(actual.upgrades())) {
            return CalibrationResult.diverged(expected.tick(),
                "upgrades",
                expected.upgrades().toString(),
                actual.upgrades().toString());
        }

        return null;
    }

    private static CalibrationBaseline loadBaseline(String resourcePath) {
        try (InputStream is = DualRunCalibrationTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Baseline not found: " + resourcePath);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return CalibrationBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline: " + resourcePath, e);
        }
    }
}
