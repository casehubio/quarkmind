package io.quarkmind.sc2.playbook;

import java.util.List;

public record WorkerCadenceSnapshot(
    int tick,
    List<WorkerSnapshot> workers
) {}
