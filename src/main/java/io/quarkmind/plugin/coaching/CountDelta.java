package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.UnitType;

public record CountDelta(
    UnitType unitType,
    BuildingType buildingType,
    int expectedDelta,
    int baselineCount
) implements VerificationPredicate {

    @Override
    public VerificationPredicate withBaseline(GameState state, LocationResolver resolver) {
        return new CountDelta(unitType, buildingType, expectedDelta, countCurrent(state));
    }

    @Override
    public boolean isSatisfied(GameState state, LocationResolver resolver) {
        int current = countCurrent(state);
        return current - baselineCount >= expectedDelta;
    }

    private int countCurrent(GameState state) {
        if (unitType != null) {
            return (int) state.myUnits().stream()
                .filter(u -> u.type() == unitType)
                .count();
        }
        if (buildingType != null) {
            return (int) state.myBuildings().stream()
                .filter(b -> b.type() == buildingType)
                .count();
        }
        return 0;
    }
}
