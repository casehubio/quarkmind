package io.quarkmind.plugin.summarisation;

public record EngagementOutcome(
        long startFrame,
        long endFrame,
        int ownUnitsLost,
        int enemyUnitsLost,
        int ownValueLost,
        int enemyValueLost,
        double unitTradeRatio,
        Outcome outcome
) {
    public enum Outcome { WON, LOST, EVEN }

    static final double WIN_MARGIN = 1.2;

    public static EngagementOutcome of(long startFrame, long endFrame,
                                       int ownUnitsLost, int enemyUnitsLost,
                                       int ownValueLost, int enemyValueLost) {
        double ratio;
        if (ownValueLost == 0 && enemyValueLost == 0) {
            ratio = 0.0;
        } else if (ownValueLost == 0) {
            ratio = Double.MAX_VALUE;
        } else {
            ratio = (double) enemyValueLost / ownValueLost;
        }

        Outcome outcome;
        if (ownValueLost == 0 && enemyValueLost == 0) {
            outcome = Outcome.EVEN;
        } else if (ownValueLost == 0) {
            outcome = Outcome.WON;
        } else if (enemyValueLost > ownValueLost * WIN_MARGIN) {
            outcome = Outcome.WON;
        } else if (ownValueLost > enemyValueLost * WIN_MARGIN) {
            outcome = Outcome.LOST;
        } else {
            outcome = Outcome.EVEN;
        }

        return new EngagementOutcome(startFrame, endFrame,
                ownUnitsLost, enemyUnitsLost, ownValueLost, enemyValueLost,
                ratio, outcome);
    }
}
