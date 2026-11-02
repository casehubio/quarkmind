package io.quarkmind.sc2.intent;

import io.quarkmind.domain.UnitType;

public record UnitDiedIntent(String unitTag, UnitType unitType) implements Intent {}
