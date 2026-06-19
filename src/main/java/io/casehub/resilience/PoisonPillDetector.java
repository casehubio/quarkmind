package io.casehub.resilience;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Circuit breaker that quarantines consistently-failing KnowledgeSources or Task types
 * to prevent resource waste. Tracks failures per source within a sliding time window
 * and quarantines the source when the threshold is breached. Quarantined KnowledgeSources
 * are skipped by ListenerEvaluator; quarantined task types are rejected by TaskBroker.
 * Quarantine expires after a configurable cool-down or can be manually released.
 *
 * @see DeadLetterQueue
 */
@ApplicationScoped
public class PoisonPillDetector {

    private final Map<String, Queue<Instant>> failureWindows = new ConcurrentHashMap<>();
    private final Map<String, Instant> quarantinedUntil = new ConcurrentHashMap<>();

    private int failureThreshold = 5;
    private Duration failureWindow = Duration.ofMinutes(10);
    private Duration quarantineDuration = Duration.ofMinutes(30);

    public void recordFailure(String sourceId, String sourceType) {
        Queue<Instant> failures = failureWindows.computeIfAbsent(sourceId,
                k -> new ConcurrentLinkedQueue<>());
        failures.add(Instant.now());

        // Evict old failures outside the window
        Instant windowStart = Instant.now().minus(failureWindow);
        failures.removeIf(t -> t.isBefore(windowStart));

        // Check threshold
        if (failures.size() >= failureThreshold) {
            quarantinedUntil.put(sourceId, Instant.now().plus(quarantineDuration));
        }
    }

    public boolean isQuarantined(String sourceId) {
        Instant until = quarantinedUntil.get(sourceId);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            quarantinedUntil.remove(sourceId);
            return false;
        }
        return true;
    }

    public void release(String sourceId) {
        quarantinedUntil.remove(sourceId);
        failureWindows.remove(sourceId);
    }

    // Configuration setters for property injection
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public void setFailureWindow(Duration failureWindow) { this.failureWindow = failureWindow; }
    public void setQuarantineDuration(Duration quarantineDuration) { this.quarantineDuration = quarantineDuration; }
}
