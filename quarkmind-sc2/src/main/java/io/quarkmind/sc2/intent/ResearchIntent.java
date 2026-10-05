package io.quarkmind.sc2.intent;

import io.quarkmind.domain.UpgradeType;

public record ResearchIntent(String buildingTag, UpgradeType upgradeType) implements Intent {}
