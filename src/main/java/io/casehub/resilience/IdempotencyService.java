package io.casehub.resilience;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTL-based cache for detecting and preventing duplicate operations. Checks whether an
 * operation (identified by its idempotency key) has already been processed and returns
 * the cached result if so. Used by TaskBroker (duplicate submissions), WorkerRegistry
 * (duplicate results), and TaskDefinition contributions (re-activation guard).
 */
@ApplicationScoped
public class IdempotencyService {

    private final Map<String, CachedOperation> cache = new ConcurrentHashMap<>();
    private Duration ttl = Duration.ofHours(24);

    public boolean isAlreadyProcessed(String idempotencyKey) {
        CachedOperation op = cache.get(idempotencyKey);
        if (op == null) return false;
        if (Instant.now().isAfter(op.expiresAt)) {
            cache.remove(idempotencyKey);
            return false;
        }
        return true;
    }

    public void markProcessed(String idempotencyKey, Object result) {
        cache.put(idempotencyKey, new CachedOperation(result, Instant.now().plus(ttl)));
    }

    public Optional<Object> getCachedResult(String idempotencyKey) {
        CachedOperation op = cache.get(idempotencyKey);
        if (op == null || Instant.now().isAfter(op.expiresAt)) {
            return Optional.empty();
        }
        return Optional.ofNullable(op.result);
    }

    public void setTtl(Duration ttl) { this.ttl = ttl; }

    private record CachedOperation(Object result, Instant expiresAt) {}
}
