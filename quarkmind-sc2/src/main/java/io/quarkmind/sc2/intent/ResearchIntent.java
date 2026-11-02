package io.quarkmind.sc2.intent;

import io.quarkmind.domain.UpgradeType;

public record ResearchIntent(String buildingTag, UpgradeType upgradeType, long commandLoop,
                             boolean trackerDriven) implements Intent {
    public ResearchIntent(String buildingTag, UpgradeType upgradeType, long commandLoop) {
        this(buildingTag, upgradeType, commandLoop, false);
    }

    public ResearchIntent(String buildingTag, UpgradeType upgradeType) {
        this(buildingTag, upgradeType, -1, false);
    }
}
