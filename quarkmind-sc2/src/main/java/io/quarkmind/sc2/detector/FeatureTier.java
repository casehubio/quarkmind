package io.quarkmind.sc2.detector;

public enum FeatureTier {
    ECONOMY(16),
    COMPOSITION(109),
    SPATIAL(11),
    FULL(438);

    private final int featureCount;

    FeatureTier(int featureCount) {
        this.featureCount = featureCount;
    }

    public int featureCount() {
        return featureCount;
    }
}
