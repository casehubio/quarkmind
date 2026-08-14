package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An EnemyStrategy that periodically re-evaluates the player's army composition
 * and switches to a counter strategy. Re-evaluation fires every {@code reEvalInterval} frames.
 *
 * <p>Internally wraps a {@link FixedBuildOrderStrategy} (the current counter).
 * When {@link #shouldSwitch} returns true, call {@link #resolveCounter} to
 * apply the switch before the next call to {@link #nextUnit}.
 *
 * Not thread-safe — all access is expected from the single game-tick scheduler thread.
 */
public class ReactiveStrategy implements EnemyStrategy {

    private static final Random RANDOM = new Random();

    private final int reEvalInterval;
    private EnemyStrategy inner;
    private String pendingCounterName;

    public ReactiveStrategy(int reEvalInterval) {
        this.reEvalInterval = reEvalInterval;
        this.inner = EnemyStrategyLibrary.randomForRace(Race.PROTOSS); // default until first eval
    }

    @Override public String            name()            { return "REACTIVE"; }
    @Override public Race              race()            { return inner.race(); }
    @Override public int               mineralsPerTick() { return inner.mineralsPerTick(); }
    @Override public EnemyAttackConfig attackConfig()    { return inner.attackConfig(); }

    @Override
    public Optional<UnitType> nextUnit(EnemyObservation obs) {
        return inner.nextUnit(obs);
    }

    @Override
    public boolean shouldSwitch(EnemyObservation obs) {
        if (obs.gameFrame() == 0) return false; // don't fire at game start
        if (obs.gameFrame() % reEvalInterval != 0) return false;
        UnitType dominant = dominant(obs.playerUnits());
        pendingCounterName = counterFor(dominant);
        return true;
    }

    /**
     * Called by EnemyBehavior when shouldSwitch() returns true.
     * Applies the pending counter by switching the inner strategy.
     * Returns {@code this} so the outer ReactiveStrategy wrapper remains active.
     */
    public EnemyStrategy resolveCounter() {
        if (pendingCounterName != null) {
            inner = EnemyStrategyLibrary.forName(pendingCounterName);
            pendingCounterName = null;
        }
        return this;
    }

    /**
     * Returns the name of the counter strategy for the given dominant unit type.
     * Package-private for testing.
     */
    String counterFor(UnitType dominant) {
        if (dominant == null) return randomStrategyName();
        return switch (dominant) {
            case STALKER, MARINE, MARAUDER, HYDRALISK,
                 VOID_RAY, PHOENIX, ORACLE -> randomRangedCounter();
            case ZEALOT, ZERGLING, BANELING -> "TERRAN_BIO_PUSH";
            case IMMORTAL, SIEGE_TANK, THOR, COLOSSUS -> "ZERG_MASS_LING";
            default -> randomStrategyName();
        };
    }

    private static UnitType dominant(List<Unit> units) {
        if (units.isEmpty()) return null;
        return units.stream()
            .collect(Collectors.groupingBy(Unit::type, Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private static String randomRangedCounter() {
        return RANDOM.nextBoolean() ? "PROTOSS_COLOSSUS_PUSH" : "ZERG_ROACH_RUSH";
    }

    private static String randomStrategyName() {
        List<String> names = new ArrayList<>(EnemyStrategyLibrary.allNames());
        names.remove("REACTIVE");
        return names.get(RANDOM.nextInt(names.size()));
    }

    @Override
    public void reset() {
        inner.reset();
        pendingCounterName = null;
    }
}
