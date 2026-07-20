package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;

public record CoachingAdvice(
    String advice,
    CoachingDomain domainTag,
    UnitType verificationUnitType,
    BuildingType verificationBuildingType,
    Integer verificationCountDelta,
    int verificationWindowFrames
) {
    private static final int MIN_WINDOW_FRAMES = 200;

    public CoachingAdvice {
        verificationWindowFrames = Math.max(verificationWindowFrames, MIN_WINDOW_FRAMES);
        if (verificationUnitType != null && verificationBuildingType != null) {
            verificationBuildingType = null;
        }
        if (verificationCountDelta == null && (verificationUnitType != null || verificationBuildingType != null)) {
            verificationUnitType = null;
            verificationBuildingType = null;
        }
    }

    public boolean isVerifiable() {
        return (verificationUnitType != null || verificationBuildingType != null)
            && verificationCountDelta != null;
    }
}
