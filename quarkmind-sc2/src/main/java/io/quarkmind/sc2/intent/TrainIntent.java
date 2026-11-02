package io.quarkmind.sc2.intent;

import io.quarkmind.domain.UnitType;

public record TrainIntent(String buildingTag, UnitType unitType, boolean trackerDriven) implements Intent {
    public TrainIntent(String buildingTag, UnitType unitType) {
        this(buildingTag, unitType, false);
    }
}
