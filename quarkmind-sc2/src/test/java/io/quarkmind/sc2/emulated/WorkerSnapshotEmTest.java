package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.WorkerAction;
import io.quarkmind.sc2.playbook.WorkerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerSnapshotEmTest {

    @Test
    void miningWorkerMapsToGatheringMinerals() {
        SimulatedWorker w = new SimulatedWorker("w1", new Point2d(10, 20), 0);
        w.task = WorkerTask.MINING_MINERALS;
        w.cargo = 0;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.tag()).isEqualTo("w1");
        assertThat(snapshot.position()).isEqualTo(new Point2d(10, 20));
        assertThat(snapshot.action()).isEqualTo(WorkerAction.GATHERING_MINERALS);
        assertThat(snapshot.carrying()).isFalse();
    }

    @Test
    void returningMineralsWithCargoMapsCorrectly() {
        SimulatedWorker w = new SimulatedWorker("w2", new Point2d(5, 5), 0);
        w.task = WorkerTask.RETURNING_MINERALS;
        w.cargo = 5.0;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_MINERALS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void depositingMineralsMapsToReturningMinerals() {
        SimulatedWorker w = new SimulatedWorker("w3", new Point2d(5, 5), 0);
        w.task = WorkerTask.DEPOSITING_MINERALS;
        w.cargo = 5.0;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_MINERALS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void harvestingGasMapsToGatheringGas() {
        SimulatedWorker w = new SimulatedWorker("w4", new Point2d(15, 15), 0);
        w.task = WorkerTask.HARVESTING_GAS;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.GATHERING_GAS);
    }

    @Test
    void returningGasWithCargoMapsCorrectly() {
        SimulatedWorker w = new SimulatedWorker("w5", new Point2d(5, 5), 0);
        w.task = WorkerTask.RETURNING_GAS;
        w.cargo = 4.0;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_GAS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void depositingGasMapsToReturningGas() {
        SimulatedWorker w = new SimulatedWorker("w6", new Point2d(5, 5), 0);
        w.task = WorkerTask.DEPOSITING_GAS;
        w.cargo = 4.0;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.RETURNING_GAS);
        assertThat(snapshot.carrying()).isTrue();
    }

    @Test
    void idleWorkerMapsToIdle() {
        SimulatedWorker w = new SimulatedWorker("w7", new Point2d(5, 5), 0);
        w.task = WorkerTask.IDLE;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.IDLE);
        assertThat(snapshot.carrying()).isFalse();
    }

    @Test
    void buildingWorkerMapsToOther() {
        SimulatedWorker w = new SimulatedWorker("w8", new Point2d(5, 5), 0);
        w.task = WorkerTask.BUILDING;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.OTHER);
    }

    @Test
    void scoutingWorkerMapsToOther() {
        SimulatedWorker w = new SimulatedWorker("w9", new Point2d(5, 5), 0);
        w.task = WorkerTask.SCOUTING;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.OTHER);
    }

    @Test
    void transferringWorkerMapsToOther() {
        SimulatedWorker w = new SimulatedWorker("w10", new Point2d(5, 5), 0);
        w.task = WorkerTask.TRANSFERRING;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.action()).isEqualTo(WorkerAction.OTHER);
    }

    @Test
    void regularWorkerHasMuleFalse() {
        SimulatedWorker w = new SimulatedWorker("w11", new Point2d(10, 20), 0);
        w.task = WorkerTask.MINING_MINERALS;

        WorkerSnapshot snapshot = toSnapshot(w);

        assertThat(snapshot.mule()).isFalse();
    }

    @Test
    void muleWorkerHasMuleTrue() {
        SimulatedWorker w = new SimulatedWorker("m1", new Point2d(10, 20), 0);
        w.task = WorkerTask.MINING_MINERALS;

        WorkerSimulation sim = new WorkerSimulation();
        sim.addMuleForTest(w);
        WorkerSnapshot snapshot = sim.workerSnapshots().getFirst();

        assertThat(snapshot.tag()).isEqualTo("m1");
        assertThat(snapshot.action()).isEqualTo(WorkerAction.GATHERING_MINERALS);
        assertThat(snapshot.mule()).isTrue();
    }

    @Test
    void workerSnapshotsIncludesBothWorkersAndMules() {
        WorkerSimulation sim = new WorkerSimulation();
        sim.addWorkerForTest(new SimulatedWorker("w12", new Point2d(10, 20), 0));
        SimulatedWorker mule = new SimulatedWorker("m2", new Point2d(15, 25), 0);
        mule.task = WorkerTask.MINING_MINERALS;
        sim.addMuleForTest(mule);

        List<WorkerSnapshot> snapshots = sim.workerSnapshots();

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).anyMatch(s -> s.tag().equals("w12") && !s.mule());
        assertThat(snapshots).anyMatch(s -> s.tag().equals("m2") && s.mule());
    }


    @Test
    void workerSimulationExposesList() {
        WorkerSimulation sim = new WorkerSimulation();
        List<WorkerSnapshot> snapshots = sim.workerSnapshots();
        assertThat(snapshots).isEmpty();
    }

    private static WorkerSnapshot toSnapshot(SimulatedWorker w) {
        WorkerSimulation sim = new WorkerSimulation();
        sim.addWorkerForTest(w);
        return sim.workerSnapshots().getFirst();
    }
}
