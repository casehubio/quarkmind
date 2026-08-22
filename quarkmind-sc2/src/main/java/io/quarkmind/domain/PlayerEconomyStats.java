package io.quarkmind.domain;

public record PlayerEconomyStats(
    int mineralsCurrent,
    int vespeneCurrent,
    int mineralsCollectionRate,
    int vespeneCollectionRate,
    int foodMade,
    int foodUsed,
    int workersActiveCount,
    int mineralsUsedCurrentArmy,
    int mineralsUsedCurrentEconomy,
    int mineralsUsedCurrentTechnology,
    int vespeneUsedCurrentArmy,
    int vespeneUsedCurrentEconomy,
    int vespeneUsedCurrentTechnology
) {
    public static final PlayerEconomyStats EMPTY = new PlayerEconomyStats(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public float[] toFeatureVector() {
        return new float[] {
            mineralsCurrent / 1000.0f,
            vespeneCurrent / 1000.0f,
            mineralsCollectionRate / 1000.0f,
            vespeneCollectionRate / 1000.0f,
            foodMade / 1000.0f,
            foodUsed / 1000.0f,
            workersActiveCount / 1000.0f,
            mineralsUsedCurrentArmy / 1000.0f,
            mineralsUsedCurrentEconomy / 1000.0f,
            mineralsUsedCurrentTechnology / 1000.0f,
            vespeneUsedCurrentArmy / 1000.0f,
            vespeneUsedCurrentEconomy / 1000.0f,
            vespeneUsedCurrentTechnology / 1000.0f
        };
    }
}
