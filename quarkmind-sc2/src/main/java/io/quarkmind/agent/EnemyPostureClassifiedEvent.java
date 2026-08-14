package io.quarkmind.agent;

/**
 * CDI event fired by {@link io.quarkmind.plugin.scouting.DroolsScoutingTask} when ENEMY_POSTURE
 * first transitions to a non-UNKNOWN value (synchronous {@code Event.fire()}).
 *
 * <p>Observed synchronously by {@link StrategyTrustObserver}, which uses it to trigger the
 * mid-game strategy checkpoint. Because the fire is synchronous and happens inside
 * {@code DroolsScoutingTask.execute()}, the pivot is effective within the same tick's
 * {@code createAndSolve()} re-evaluation pass — not the next tick.
 *
 * <p>{@code postureDispatchEnabled} (the preference controlling advisory/broker dispatch) does
 * NOT gate this event — trust routing is an independent consumer of posture classification.
 */
public record EnemyPostureClassifiedEvent(String posture) {}
