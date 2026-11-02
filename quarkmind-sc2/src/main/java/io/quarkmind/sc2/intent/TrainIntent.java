package io.quarkmind.sc2.intent;

import io.quarkmind.domain.UnitType;

public record TrainIntent(String buildingTag, UnitType unitType, boolean trackerDriven,
                          boolean warpedIn) implements Intent {
    public TrainIntent(String buildingTag, UnitType unitType, boolean trackerDriven) {
        this(buildingTag, unitType, trackerDriven, false);
    }

    public TrainIntent(String buildingTag, UnitType unitType) {
        this(buildingTag, unitType, false, false);
    }
}
