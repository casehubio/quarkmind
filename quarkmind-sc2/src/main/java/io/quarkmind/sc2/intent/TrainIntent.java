package io.quarkmind.sc2.intent;

import io.quarkmind.domain.UnitType;

public record TrainIntent(String buildingTag, UnitType unitType) implements Intent {
}
