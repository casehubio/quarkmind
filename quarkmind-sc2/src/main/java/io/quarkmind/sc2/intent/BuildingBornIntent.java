package io.quarkmind.sc2.intent;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;

public record BuildingBornIntent(String tag, BuildingType type, Point2d position) implements Intent {}
