package io.casehub.core;

import io.casehub.coordination.PropagationContext;
import io.casehub.error.ErrorInfo;
import io.casehub.error.StaleVersionException;
import io.casehub.worker.Task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The shared workspace at the heart of the Blackboard Architecture.
 * Each CaseFile has a Long primary key (for database storage and optimistic locking),
 * a UUID for OpenTelemetry trace correlation, and direct references to its parent
 * and children — forming the POJO object graph.
 */
public interface CaseFile {

    // Identity
    Long getId();
    Long getVersion();
    UUID getOtelTraceId();
    String getCaseType();

    // Read shared workspace state
    <T> Optional<T> get(String key, Class<T> type);
    boolean contains(String key);
    Set<String> keys();
    Map<String, Object> snapshot();

    // Write contributions (triggers change events)
    void put(String key, Object value);
    void putIfAbsent(String key, Object value);

    // Optimistic concurrency (fine-grained, per-key)
    void putIfVersion(String key, Object value, long expectedVersion) throws StaleVersionException;
    long getKeyVersion(String key);

    // Change listeners (in-memory only; not persisted)
    void onChange(String key, Consumer<CaseFileItemEvent> listener);
    void onAnyChange(Consumer<CaseFileItemEvent> listener);

    // Context propagation (tracing + budget)
    PropagationContext getPropagationContext();

    // Graph relationships
    Optional<CaseFile> getParentCase();
    List<CaseFile> getChildCases();
    List<Task> getTasks();

    // Lifecycle
    CaseStatus getStatus();
    void setStatus(CaseStatus status);
    Instant getCreatedAt();
    void complete();
    void fail(ErrorInfo error);
}
