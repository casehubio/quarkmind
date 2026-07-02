package io.quarkmind.agent;

import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which advisors have been invoked during the current game.
 *
 * <p>Cleared on {@link GameStarted} to reset the invocation set for each game session.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap#newKeySet()}.
 *
 * <p>Refs #180
 */
@ApplicationScoped
public class AdvisoryInvocationCounter {

    private final Set<String> invokedAdvisors = ConcurrentHashMap.newKeySet();

    /**
     * Records that an advisor was invoked.
     *
     * @param advisorId agent identifier (e.g., "claude:crisis-aggressive@v1")
     */
    public void record(String advisorId) {
        invokedAdvisors.add(advisorId);
    }

    /**
     * Returns an immutable snapshot of the invoked advisors set.
     *
     * @return immutable copy of advisor IDs invoked in this game
     */
    public Set<String> snapshot() {
        return Set.copyOf(invokedAdvisors);
    }

    /**
     * Clears the invoked advisors set when a new game starts.
     *
     * <p>Uses {@code @Observes} (synchronous) per protocol PP-20260610-88dbbd.
     */
    void onGameStarted(@Observes GameStarted event) {
        invokedAdvisors.clear();
    }
}
