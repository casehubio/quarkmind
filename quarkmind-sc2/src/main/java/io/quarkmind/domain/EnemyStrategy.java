package io.quarkmind.domain;

import java.util.Optional;

public interface EnemyStrategy {
    String name();
    Race race();
    int mineralsPerTick();
    EnemyAttackConfig attackConfig();

    /**
     * Returns the next unit type this strategy wants to train.
     * Called by EnemyBehavior only when it is ready to queue a new unit.
     * Implementations advance their internal build pointer on each call.
     */
    Optional<UnitType> nextUnit(EnemyObservation obs);

    /**
     * Returns true when the strategy wants to hand control to a different strategy.
     */
    boolean shouldSwitch(EnemyObservation obs);

    /** Called by EnemyBehavior when this strategy is replaced. Resets internal state. */
    default void reset() {}
}
