package io.quarkmind.domain;

import java.util.List;
import java.util.Set;

public record EnemyObservation(
    List<Unit>        playerUnits,
    Set<BuildingType> enemyBuildings,
    int               minerals,
    long              gameFrame
) {}
