package io.quarkmind.sc2.intent;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;

public record BuildIntent(String unitTag, BuildingType buildingType, Point2d location,
                          String replayTag, long completionLoop, AddonKind addonKind) implements Intent {

    public BuildIntent(String unitTag, BuildingType buildingType, Point2d location,
                       String replayTag, long completionLoop) {
        this(unitTag, buildingType, location, replayTag, completionLoop, AddonKind.NONE);
    }

    public static BuildIntent of(String unitTag, BuildingType buildingType, Point2d location) {
        return new BuildIntent(unitTag, buildingType, location, null, -1, AddonKind.NONE);
    }
}
