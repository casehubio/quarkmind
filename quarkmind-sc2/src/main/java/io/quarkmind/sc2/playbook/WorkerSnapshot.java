package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.WorkerAction;

public record WorkerSnapshot(
    String tag,
    Point2d position,
    WorkerAction action,
    boolean carrying
) {}
