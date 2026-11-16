package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

import java.util.ArrayList;
import java.util.List;

class MineralPatch {
    static final double CLOSE_PATCH_MINERALS   = 1500.0;
    static final double FAR_PATCH_MINERALS     = 750.0;
    static float  PATCH_DISTANCE         = WorkerPhysicsConfig.DEFAULT_PATCH_DISTANCE;
    static final int    PATCHES_PER_BASE       = 8;
    static final int    CLOSE_PATCHES_PER_BASE = 4;
    static final float  CLOSE_PATCH_DISTANCE   = 6.7f;
    static final float  FAR_PATCH_DISTANCE     = 7.5f;

    final   Point2d position;
    final   int     baseIndex;
    private double  remainingMinerals;
    static final int MAX_MINERS = 1;
    final List<SimulatedWorker> miners = new ArrayList<>(MAX_MINERS);
    final boolean rich;
    double lockEndLoop;
    double distanceToBase;
    double approachDockingLoops = SimulatedWorker.APPROACH_DOCKING_LOOPS;
    static double APPROACH_EQUALIZATION_SCALE = 0.4;


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

    boolean isIdle(double currentLoop) {
        return miners.isEmpty() && currentLoop >= lockEndLoop;
    }


    static void computeApproachDocking(List<MineralPatch> patches, Point2d basePos) {
        double townHallRadius = WorkerSimulation.TOWN_HALL_RADIUS;
        double patchRadius    = WorkerSimulation.MINERAL_PATCH_RADIUS;
        double loopsPerUnit = io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND
                              / io.quarkmind.domain.SC2Data.unitSpeed(io.quarkmind.domain.UnitType.PROBE);

        double[] e2eDistances = new double[patches.size()];
        for (int i = 0; i < patches.size(); i++) {
            MineralPatch p = patches.get(i);
            p.distanceToBase = p.position.distanceTo(basePos);
            e2eDistances[i]  = Math.max(0, p.distanceToBase - townHallRadius - patchRadius);
        }

        double[] sorted = e2eDistances.clone();
        java.util.Arrays.sort(sorted);
        double medianE2E = sorted.length % 2 == 0
                           ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0
                           : sorted[sorted.length / 2];

        double baseApproach = SimulatedWorker.APPROACH_DOCKING_LOOPS;
        for (int i = 0; i < patches.size(); i++) {
            double adjustment = (medianE2E - e2eDistances[i]) * loopsPerUnit * APPROACH_EQUALIZATION_SCALE;
            patches.get(i).approachDockingLoops = Math.max(0, baseApproach + adjustment);
        }
    }

    static Point2d[] patchPositions(Point2d townHallPos) {
        Point2d[] positions = new Point2d[PATCHES_PER_BASE];
        for (int i = 0; i < PATCHES_PER_BASE; i++) {
            double angle = Math.PI * (0.2 + 0.6 * i / (PATCHES_PER_BASE - 1));
            float  dist  = i < CLOSE_PATCHES_PER_BASE ? CLOSE_PATCH_DISTANCE : FAR_PATCH_DISTANCE;
            float  px    = townHallPos.x() + (float) (dist * Math.cos(angle));
            float  py    = townHallPos.y() + (float) (dist * Math.sin(angle));
            positions[i] = new Point2d(px, py);
        }
        return positions;
    }
}
