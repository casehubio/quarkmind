package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CalibrationBaselineTest {

    @Test
    void snapshotFromGameState() {
        GameState state = new GameState(
            150, 50, 23, 14,
            List.of(
                new Unit("p1", UnitType.PROBE, new Point2d(30, 30), 40, 40, 20, 20, 0, 0),
                new Unit("p2", UnitType.PROBE, new Point2d(31, 30), 40, 40, 20, 20, 0, 0),
                new Unit("z1", UnitType.ZEALOT, new Point2d(32, 30), 100, 100, 50, 50, 0, 0)
            ),
            List.of(
                new Building("n1", BuildingType.NEXUS, new Point2d(30, 30), 1000, 1000, true),
                new Building("py1", BuildingType.PYLON, new Point2d(35, 30), 200, 200, true)
            ),
            List.of(), List.of(), List.of(),
            List.of(), List.of(), 0L, null, null, null,
            Set.of("WarpGateResearch"), Set.of()
        );

        CalibrationSnapshot snap = CalibrationSnapshot.from(42, state);

        assertEquals(42, snap.tick());
        assertEquals(150, snap.minerals());
        assertEquals(50, snap.vespene());
        assertEquals(23, snap.supply());
        assertEquals(14, snap.supplyUsed());
        assertEquals(Map.of("PROBE", 2, "ZEALOT", 1), snap.units());
        assertEquals(Map.of("NEXUS", 1, "PYLON", 1), snap.buildings());
        assertEquals(Set.of("WarpGateResearch"), snap.upgrades());
    }

    @Test
    void snapshotExcludesIncompleteBuildings() {
        GameState state = new GameState(
            100, 0, 15, 12,
            List.of(),
            List.of(
                new Building("n1", BuildingType.NEXUS, new Point2d(30, 30), 1000, 1000, true),
                new Building("py1", BuildingType.PYLON, new Point2d(35, 30), 50, 200, false)
            ),
            List.of(), List.of(), List.of(),
            List.of(), List.of(), 0L, null, null, null,
            Set.of(), Set.of()
        );

        CalibrationSnapshot snap = CalibrationSnapshot.from(0, state);

        assertEquals(Map.of("NEXUS", 1), snap.buildings());
    }

    @Test
    void baselineJsonRoundTrip() {
        CalibrationSnapshot snap0 = new CalibrationSnapshot(
            0, 50, 0, 15, 12,
            Map.of("PROBE", 12),
            Map.of("NEXUS", 1),
            Set.of()
        );
        CalibrationSnapshot snap1 = new CalibrationSnapshot(
            1, 51, 0, 15, 12,
            Map.of("PROBE", 12),
            Map.of("NEXUS", 1),
            Set.of()
        );

        CalibrationBaseline baseline = new CalibrationBaseline(
            "economy-only-protoss", "PROTOSS", "5.0.16.90136",
            "2026-10-30T14:00:00Z", 2,
            List.of(snap0, snap1)
        );

        String json = baseline.toJson();
        CalibrationBaseline restored = CalibrationBaseline.fromJson(json);

        assertEquals(baseline.playbook(), restored.playbook());
        assertEquals(baseline.race(), restored.race());
        assertEquals(baseline.sc2Build(), restored.sc2Build());
        assertEquals(baseline.tickCount(), restored.tickCount());
        assertEquals(2, restored.snapshots().size());
        assertEquals(50, restored.snapshots().get(0).minerals());
        assertEquals(51, restored.snapshots().get(1).minerals());
        assertEquals(Set.of(), restored.snapshots().get(0).upgrades());
    }

    @Test
    void calibrationResultDivergedReport() {
        CalibrationResult result = CalibrationResult.diverged(
            42, "units.PROBE", "16", "15"
        );

        assertFalse(result.passed());
        assertEquals(42, result.divergenceTick());
        assertTrue(result.report().contains("tick 42"));
        assertTrue(result.report().contains("units.PROBE"));
        assertTrue(result.report().contains("expected 16"));
        assertTrue(result.report().contains("actual 15"));
    }

    @Test
    void calibrationResultPassedReport() {
        CalibrationResult result = CalibrationResult.passed(305);

        assertTrue(result.passed());
        assertEquals(-1, result.divergenceTick());
        assertTrue(result.report().contains("305 ticks"));
    }
}
