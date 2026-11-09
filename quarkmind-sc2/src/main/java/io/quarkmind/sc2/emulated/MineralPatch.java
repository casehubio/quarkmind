package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

import java.util.ArrayList;
import java.util.List;

class MineralPatch {
    static final double CLOSE_PATCH_MINERALS   = 1500.0;
    static final double FAR_PATCH_MINERALS     = 750.0;
    static final float  PATCH_DISTANCE         = 7.0f;
    static final int    PATCHES_PER_BASE       = 8;
    static final int    CLOSE_PATCHES_PER_BASE = 4;

    final   Point2d position;
    final   int     baseIndex;
    private double  remainingMinerals;
    static final int MAX_MINERS = 1;
    final List<SimulatedWorker> miners = new ArrayList<>(MAX_MINERS);
    final boolean rich;
    double lockEndLoop;


    MineralPatch(Point2d position, int baseIndex, double initialMinerals, boolean rich) {
        this.position          = position;
        this.baseIndex         = baseIndex;
        this.remainingMinerals = initialMinerals;
        this.rich              = rich;
    }

    MineralPatch(Point2d position, int baseIndex, double initialMinerals) {
        this(position, baseIndex, initialMinerals, false);
    }


    boolean isDepleted() {
        return remainingMinerals <= 0;
    }

    double harvest(double amount) {
        double harvested = Math.min(amount, remainingMinerals);
        remainingMinerals -= harvested;
        return harvested;
    }

    double remainingMinerals() {
        return remainingMinerals;
    }

    static Point2d[] patchPositions(Point2d townHallPos) {
        Point2d[] positions = new Point2d[PATCHES_PER_BASE];
        for (int i = 0; i < PATCHES_PER_BASE; i++) {
            double angle = Math.PI * (0.2 + 0.6 * i / (PATCHES_PER_BASE - 1));
            float  px    = townHallPos.x() + (float) (PATCH_DISTANCE * Math.cos(angle));
            float  py    = townHallPos.y() + (float) (PATCH_DISTANCE * Math.sin(angle));
            positions[i] = new Point2d(px, py);
        }
        return positions;
    }
}
