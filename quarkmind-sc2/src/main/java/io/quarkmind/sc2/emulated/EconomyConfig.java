package io.quarkmind.sc2.emulated;

public record EconomyConfig(int rateIntervalLoops) {

    public static final int DEFAULT_RATE_INTERVAL_LOOPS = 160;

    public static EconomyConfig defaults() {
        return new EconomyConfig(DEFAULT_RATE_INTERVAL_LOOPS);
    }
}
