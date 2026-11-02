package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerAssignmentTest {

    private WorkerAssignment assignment;

    @BeforeEach
    void setUp() {
        assignment = new WorkerAssignment();
    }

    @Test
    void initialWorkers_assignedToBase() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        assignment.onInitialWorkers(
            List.of("probe-0", "probe-1", "probe-2", "probe-3",
                    "probe-4", "probe-5", "probe-6", "probe-7",
                    "probe-8", "probe-9", "probe-10", "probe-11"),
            new Point2d(8, 8), "nexus-0");
        int[] miners = assignment.mineralWorkersPerBase();
        assertEquals(1, miners.length);
        assertEquals(12, miners[0]);
    }

    @Test
    void workerProduced_assignedToNearestBase() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        assignment.onTownHallCompleted("nexus-1", new Point2d(50, 50));
        assignment.onWorkerProduced("probe-new", new Point2d(9, 9));
        int[] miners = assignment.mineralWorkersPerBase();
        assertEquals(1, miners[0]);
        assertEquals(0, miners[1]);
    }

    @Test
    void gasBuildingCompleted_transfers3MineralWorkersToGas() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        for (int i = 0; i < 12; i++) {
            assignment.onWorkerProduced("probe-" + i, new Point2d(9, 9));
        }
        assignment.onGasBuildingCompleted("assim-0", new Point2d(5, 11));
        assertEquals(9, assignment.mineralWorkersPerBase()[0]);
        assertEquals(3, assignment.gasWorkersForBuilding("assim-0"));
    }

    @Test
    void gasBuildingCompleted_fewerThan3Available() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        assignment.onWorkerProduced("probe-0", new Point2d(9, 9));
        assignment.onWorkerProduced("probe-1", new Point2d(9, 9));
        assignment.onGasBuildingCompleted("assim-0", new Point2d(5, 11));
        assertEquals(0, assignment.mineralWorkersPerBase()[0]);
        assertEquals(2, assignment.gasWorkersForBuilding("assim-0"));
    }

    @Test
    void workerDied_removedFromMineralLine() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        assignment.onWorkerProduced("probe-0", new Point2d(9, 9));
        assignment.onWorkerProduced("probe-1", new Point2d(9, 9));
        assignment.onWorkerDied("probe-0");
        assertEquals(1, assignment.mineralWorkersPerBase()[0]);
    }

    @Test
    void workerDied_removedFromGasLine() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        for (int i = 0; i < 6; i++) {
            assignment.onWorkerProduced("probe-" + i, new Point2d(9, 9));
        }
        assignment.onGasBuildingCompleted("assim-0", new Point2d(5, 11));
        assertEquals(3, assignment.gasWorkersForBuilding("assim-0"));
        assignment.onWorkerDied("probe-0");
        assertEquals(2, assignment.gasWorkersForBuilding("assim-0"));
        assertEquals(3, assignment.mineralWorkersPerBase()[0]);
    }

    @Test
    void townHallCompleted_redistributesFromOversaturatedBase() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        for (int i = 0; i < 20; i++) {
            assignment.onWorkerProduced("probe-" + i, new Point2d(9, 9));
        }
        assertEquals(20, assignment.mineralWorkersPerBase()[0]);
        assignment.onTownHallCompleted("nexus-1", new Point2d(50, 50));
        int[] miners = assignment.mineralWorkersPerBase();
        assertEquals(16, miners[0]);
        assertEquals(4, miners[1]);
    }

    @Test
    void workerProduced_noBases_unassigned() {
        assignment.onWorkerProduced("probe-0", new Point2d(9, 9));
        assertEquals(0, assignment.mineralWorkersPerBase().length);
    }

    @Test
    void reset_clearsAllState() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        assignment.onWorkerProduced("probe-0", new Point2d(9, 9));
        assignment.reset();
        assertEquals(0, assignment.mineralWorkersPerBase().length);
    }

    @Test
    void townHallCompleted_duplicateTag_idempotent() {
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        assignment.onTownHallCompleted("nexus-0", new Point2d(8, 8));
        assertEquals(1, assignment.mineralWorkersPerBase().length);
    }
}
