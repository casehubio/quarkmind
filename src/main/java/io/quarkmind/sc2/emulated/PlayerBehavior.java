package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.IntentQueue;

/**
 * Drives a player's decisions each game tick by pushing Intents into the provided queue.
 *
 * <p>Friendly side: {@link io.quarkmind.agent.AgentOrchestrator} fills the friendly
 * IntentQueue asynchronously via the Quarkus scheduler — its adapter's tick() is a no-op
 * since the queue is filled externally.
 *
 * <p>Enemy side: {@link EnemyBehavior} fills the enemy IntentQueue synchronously during
 * {@link EmulatedGame#tick()}.
 */
public interface PlayerBehavior {
    void tick(GameState observation, IntentQueue queue);
}
