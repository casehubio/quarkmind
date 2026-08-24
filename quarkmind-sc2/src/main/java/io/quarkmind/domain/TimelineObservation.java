package io.quarkmind.domain;

public record TimelineObservation(
        double minute,
        int ourWorkers,
        int ourMinerals,
        int ourArmySupply
) {
    public static TimelineObservation from(GameState gs) {
        int workers = (int) gs.myUnits().stream()
                .filter(u -> u.type().isWorker())
                .count();
        return new TimelineObservation(
                gs.gameFrame() / SC2Data.GAME_LOOPS_PER_SECOND / 60.0,
                workers,
                gs.minerals(),
                gs.supplyUsed() - workers
        );
    }
}
