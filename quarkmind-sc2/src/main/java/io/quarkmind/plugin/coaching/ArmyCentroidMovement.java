package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;

public record ArmyCentroidMovement(
    MovementDirection direction,
    LocationReference referencePoint,
    double minDistance,
    Point2d baselineCentroid
) implements VerificationPredicate {

    @Override
    public VerificationPredicate withBaseline(GameState state, LocationResolver resolver) {
        Point2d centroid = armyCentroid(state);
        return new ArmyCentroidMovement(direction, referencePoint, minDistance, centroid);
    }

    @Override
    public boolean isSatisfied(GameState state, LocationResolver resolver) {
        if (baselineCentroid == null) return false;
        Point2d currentCentroid = armyCentroid(state);
        if (currentCentroid == null) return false;
        Point2d ref = resolver.resolve(referencePoint, state);
        if (ref == null) return false;

        double baselineDist = baselineCentroid.distanceTo(ref);
        double currentDist = currentCentroid.distanceTo(ref);

        return switch (direction) {
            case RETREAT -> currentDist - baselineDist >= minDistance;
            case ADVANCE -> baselineDist - currentDist >= minDistance;
        };
    }

    private static Point2d armyCentroid(GameState state) {
        var army = state.myUnits().stream()
            .filter(u -> !u.type().isWorker())
            .toList();
        return Point2d.centroidOf(army);
    }
}
