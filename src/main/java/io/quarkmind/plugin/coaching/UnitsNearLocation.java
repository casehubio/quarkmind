package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;

public record UnitsNearLocation(
    UnitType unitType,
    LocationReference location,
    double radius,
    int minCount
) implements VerificationPredicate {

    @Override
    public VerificationPredicate withBaseline(GameState state, LocationResolver resolver) {
        return this;
    }

    @Override
    public boolean isSatisfied(GameState state, LocationResolver resolver) {
        Point2d target = resolver.resolve(location, state);
        if (target == null) return false;

        long count = state.myUnits().stream()
            .filter(u -> unitType != null ? u.type() == unitType : !u.type().isWorker())
            .filter(u -> u.position().distanceTo(target) <= radius)
            .count();
        return count >= minCount;
    }
}
