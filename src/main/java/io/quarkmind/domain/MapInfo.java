package io.quarkmind.domain;

import java.util.List;

public record MapInfo(
    Point2d playerStart,
    Point2d enemyStart,
    int mapWidth,
    int mapHeight,
    List<ExpansionLocation> expansions,
    List<NeutralFeature> neutralFeatures,
    List<Point2d> rampPositions
) {
    public MapInfo {
        expansions = List.copyOf(expansions);
        neutralFeatures = List.copyOf(neutralFeatures);
        rampPositions = List.copyOf(rampPositions);
    }
}
