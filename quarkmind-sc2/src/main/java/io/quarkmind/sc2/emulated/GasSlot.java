package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class GasSlot {
    static final int MAX_WORKERS = 3;

    final String buildingTag;
    final Point2d position;
    final int baseIndex;
    final List<SimulatedWorker> assignedWorkers = new ArrayList<>(MAX_WORKERS);
    final Set<SimulatedWorker> insideWorkers = new HashSet<>(MAX_WORKERS);

    GasSlot(String buildingTag, Point2d position, int baseIndex) {
        this.buildingTag = buildingTag;
        this.position = position;
        this.baseIndex = baseIndex;
    }

    boolean canEnter() {
        return insideWorkers.size() < MAX_WORKERS;
    }

    void enterBuilding(SimulatedWorker w) {
        insideWorkers.add(w);
        w.cycleLoopsRemaining = SimulatedWorker.GAS_HARVEST_DURATION_LOOPS;
    }

    void exitBuilding(SimulatedWorker w) {
        insideWorkers.remove(w);
    }

    void removeWorker(SimulatedWorker w) {
        assignedWorkers.remove(w);
        insideWorkers.remove(w);
    }
}
