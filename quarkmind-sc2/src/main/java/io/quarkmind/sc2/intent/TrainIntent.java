package io.quarkmind.sc2.intent;

import io.quarkmind.domain.UnitType;

public record TrainIntent(String buildingTag, UnitType unitType, boolean trackerDriven,
                          boolean warpedIn, long completionLoop,
                          String replayUnitTag) implements Intent {
    public TrainIntent(String buildingTag, UnitType unitType, boolean trackerDriven, boolean warpedIn, long completionLoop) {
        this(buildingTag, unitType, trackerDriven, warpedIn, completionLoop, null);
    }

    public TrainIntent(String buildingTag, UnitType unitType, boolean trackerDriven, boolean warpedIn) {
        this(buildingTag, unitType, trackerDriven, warpedIn, -1, null);
    }

    public TrainIntent(String buildingTag, UnitType unitType, boolean trackerDriven) {
        this(buildingTag, unitType, trackerDriven, false, -1, null);
    }

    public TrainIntent(String buildingTag, UnitType unitType) {
        this(buildingTag, unitType, false, false, -1, null);
    }
}
