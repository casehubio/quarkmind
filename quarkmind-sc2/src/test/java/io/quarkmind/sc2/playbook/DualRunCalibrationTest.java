package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.Race;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
    void earlyExitOnUnitMismatch() {
        CalibrationBaseline baseline = new CalibrationBaseline(
            "test", "PROTOSS", "fixture", "2026-10-30T00:00:00Z", 3,
            List.of(
                new CalibrationSnapshot(0, 50, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(1, 62, 0, 15, 12, Map.of("PROBE", 12), Map.of("NEXUS", 1), Set.of()),
                new CalibrationSnapshot(2, 74, 0, 15, 12, Map.of("PROBE", 99), Map.of("NEXUS", 1), Set.of())
            )
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
            )
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
            )
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
            List.of(tick0, tick1)
        );

        CalibrationResult result = calibrate("playbooks/economy-only-protoss.yaml", baseline);

        assertTrue(result.passed(), "Self-baseline should always pass: " + result.report());
    }

    static CalibrationResult calibrate(String playbookResource, CalibrationBaseline baseline) {
        String yaml = SC2PlaybookRunner.loadResource(playbookResource);
        JsonNode root;
        try { root = YAML.readTree(yaml); }
        catch (Exception e) { throw new RuntimeException("Failed to parse playbook", e); }

        Race race = SC2PlaybookRunner.resolveRace(root);
        List<SC2PlaybookRunner.PlaybookStep> steps = SC2PlaybookRunner.parseSteps(root);

        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        game.reset();
        SC2DeliveryHandler handler = new SC2DeliveryHandler(game);

        boolean[] fired = new boolean[steps.size()];
        int tickLimit = Math.min(baseline.tickCount(), baseline.snapshots().size());

        for (int tick = 0; tick < tickLimit; tick++) {
            game.tick();

            int supplyUsed = game.snapshot().supplyUsed();
            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                boolean canFire = step.shouldFire(tick, supplyUsed)
                    && (!fired[i] || step.loop());
                if (canFire) {
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

            CalibrationSnapshot expected = baseline.snapshots().get(tick);
            CalibrationSnapshot actual = CalibrationSnapshot.from(tick, game.snapshot());

            CalibrationResult divergence = compare(expected, actual);
            if (divergence != null) return divergence;

            if (expected.minerals() != actual.minerals() || expected.vespene() != actual.vespene()) {
                System.out.printf("  [econ] tick %d: minerals %d vs %d, vespene %d vs %d%n",
                    tick, expected.minerals(), actual.minerals(),
                    expected.vespene(), actual.vespene());
            }
        }

        return CalibrationResult.passed(tickLimit);
    }

    private static CalibrationResult compare(CalibrationSnapshot expected, CalibrationSnapshot actual) {
        for (Map.Entry<String, Integer> entry : expected.units().entrySet()) {
            int actualCount = actual.units().getOrDefault(entry.getKey(), 0);
            if (actualCount != entry.getValue()) {
                return CalibrationResult.diverged(expected.tick(),
                    "units." + entry.getKey(),
                    String.valueOf(entry.getValue()),
                    String.valueOf(actualCount));
            }
        }
        for (Map.Entry<String, Integer> entry : actual.units().entrySet()) {
            if (!expected.units().containsKey(entry.getKey()) && entry.getValue() > 0) {
                return CalibrationResult.diverged(expected.tick(),
                    "units." + entry.getKey(),
                    "0",
                    String.valueOf(entry.getValue()));
            }
        }

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
