package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

class GasSlot {
    static final int MAX_WORKERS = 3;

    final String buildingTag;
    final Point2d position;
    final int baseIndex;
    final List<SimulatedWorker> assignedWorkers = new ArrayList<>(MAX_WORKERS);
    SimulatedWorker insideWorker;
    final Deque<SimulatedWorker> waitQueue = new ArrayDeque<>();

    GasSlot(String buildingTag, Point2d position, int baseIndex) {
        this.buildingTag = buildingTag;
        this.position = position;
        this.baseIndex = baseIndex;
    }

    void removeWorker(SimulatedWorker w) {
        assignedWorkers.remove(w);
        waitQueue.remove(w);
        if (insideWorker == w) insideWorker = null;
    }
}
