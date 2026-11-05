package io.quarkmind.sc2.intent;

import io.quarkmind.domain.BuildingType;

public record BuildingDiedIntent(String tag, BuildingType type) implements Intent {}
