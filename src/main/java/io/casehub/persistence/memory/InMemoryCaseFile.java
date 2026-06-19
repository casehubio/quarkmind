package io.casehub.persistence.memory;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.core.CaseFileItemEvent;
import io.casehub.core.CaseStatus;
import io.casehub.error.ErrorInfo;
import io.casehub.error.StaleVersionException;
import io.casehub.worker.Task;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * In-memory implementation of {@link CaseFile}.
 * Thread-safe: workspace uses ConcurrentHashMap, status uses AtomicReference,
 * graph collections use CopyOnWriteArrayList.
 */
class InMemoryCaseFile implements CaseFile {

    private static final AtomicLong ID_SEQ = new AtomicLong(0);

    private final Long id = ID_SEQ.incrementAndGet();
    private final UUID otelTraceId = UUID.randomUUID();
    private final String caseType;
    private final PropagationContext propagationContext;
    private final Instant createdAt = Instant.now();

    // Workspace
    private final ConcurrentHashMap<String, AtomicReference<ItemEntry>> store = new ConcurrentHashMap<>();
    private final AtomicLong writeVersion = new AtomicLong(0); // per-write version counter

    // Status
    private final AtomicReference<CaseStatus> status = new AtomicReference<>(CaseStatus.PENDING);

    // Graph
    private volatile CaseFile parentCase;
    private final CopyOnWriteArrayList<CaseFile> childCases = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Task> tasks = new CopyOnWriteArrayList<>();

    // Listeners (ephemeral, not persisted)
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<CaseFileItemEvent>>> keyListeners
            = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<CaseFileItemEvent>> anyChangeListeners
            = new CopyOnWriteArrayList<>();

    InMemoryCaseFile(String caseType, Map<String, Object> initialState,
                     PropagationContext propagationContext) {
        this.caseType = caseType;
        this.propagationContext = propagationContext;
        if (initialState != null) {
            initialState.forEach((k, v) -> {
                if (v != null) {
                    store.put(k, new AtomicReference<>(new ItemEntry(v, writeVersion.incrementAndGet())));
                }
            });
        }
    }

    // --- Internal wiring (package-private, not on interface) ---
    void setParentCase(CaseFile parent) { this.parentCase = parent; }
    void addChildCase(CaseFile child)   { this.childCases.add(child); }
    void addTask(Task task)             { this.tasks.add(task); }

    // --- Identity ---
    @Override public Long getId()           { return id; }
    @Override public Long getVersion()      { return writeVersion.get(); }
    @Override public UUID getOtelTraceId()  { return otelTraceId; }
    @Override public String getCaseType()   { return caseType; }

    // --- Read workspace ---
    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        AtomicReference<ItemEntry> ref = store.get(key);
        if (ref == null) return Optional.empty();
        ItemEntry e = ref.get();
        return e == null ? Optional.empty() : Optional.of(type.cast(e.value()));
    }

    @Override public boolean contains(String key) {
        AtomicReference<ItemEntry> ref = store.get(key);
        return ref != null && ref.get() != null;
    }

    @Override public Set<String> keys() {
        return store.entrySet().stream()
                .filter(e -> e.getValue().get() != null)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        store.forEach((k, ref) -> {
            ItemEntry e = ref.get();
            if (e != null) snap.put(k, e.value());
        });
        return Collections.unmodifiableMap(snap);
    }

    // --- Write workspace ---
    @Override public void put(String key, Object value) {
        if (value == null) return;
        AtomicReference<ItemEntry> ref = store.computeIfAbsent(key, k -> new AtomicReference<>());
        ItemEntry previous = ref.getAndSet(new ItemEntry(value, writeVersion.incrementAndGet()));
        fireEvent(key, value, previous);
    }

    @Override public void putIfAbsent(String key, Object value) {
        if (value == null) return;
        AtomicReference<ItemEntry> ref = store.computeIfAbsent(key, k -> new AtomicReference<>());
        long ver = writeVersion.incrementAndGet();
        if (ref.compareAndSet(null, new ItemEntry(value, ver))) {
            fireEvent(key, value, null);
        }
    }

    @Override public void putIfVersion(String key, Object value, long expectedVersion)
            throws StaleVersionException {
        if (value == null) throw new IllegalArgumentException("putIfVersion: value must not be null");
        AtomicReference<ItemEntry> ref = store.computeIfAbsent(key, k -> new AtomicReference<>());
        ItemEntry current;
        do {
            current = ref.get();
            long currentVer = current != null ? current.version() : 0L;
            if (currentVer != expectedVersion) {
                throw new StaleVersionException(key, expectedVersion, currentVer);
            }
        } while (!ref.compareAndSet(current, new ItemEntry(value, writeVersion.incrementAndGet())));
        fireEvent(key, value, current);
    }

    @Override public long getKeyVersion(String key) {
        AtomicReference<ItemEntry> ref = store.get(key);
        if (ref == null) return 0L;
        ItemEntry e = ref.get();
        return e != null ? e.version() : 0L;
    }

    // --- Listeners ---
    @Override public void onChange(String key, Consumer<CaseFileItemEvent> listener) {
        keyListeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override public void onAnyChange(Consumer<CaseFileItemEvent> listener) {
        anyChangeListeners.add(listener);
    }

    // --- Context ---
    @Override public PropagationContext getPropagationContext() { return propagationContext; }

    // --- Graph ---
    @Override public Optional<CaseFile> getParentCase()  { return Optional.ofNullable(parentCase); }
    @Override public List<CaseFile> getChildCases()      { return Collections.unmodifiableList(childCases); }
    @Override public List<Task> getTasks()               { return Collections.unmodifiableList(tasks); }

    // --- Lifecycle ---
    @Override public CaseStatus getStatus()         { return status.get(); }
    @Override public void setStatus(CaseStatus s)   { status.set(s); }
    @Override public Instant getCreatedAt()         { return createdAt; }

    @Override public void complete() {
        CaseStatus current = status.get();
        if (current == CaseStatus.COMPLETED) return;
        if (isTerminal(current)) throw new IllegalStateException("Cannot complete from " + current);
        status.compareAndSet(current, CaseStatus.COMPLETED);
    }

    @Override public void fail(ErrorInfo error) {
        CaseStatus current = status.get();
        if (current == CaseStatus.FAULTED) return;
        if (isTerminal(current)) throw new IllegalStateException("Cannot fail from " + current);
        status.compareAndSet(current, CaseStatus.FAULTED);
    }

    private boolean isTerminal(CaseStatus s) {
        return s == CaseStatus.COMPLETED || s == CaseStatus.FAULTED || s == CaseStatus.CANCELLED;
    }

    private void fireEvent(String key, Object value, ItemEntry previous) {
        Optional<Object> prev = previous != null ? Optional.of(previous.value()) : Optional.empty();
        CaseFileItemEvent event = new CaseFileItemEvent(id.toString(), key, value, prev, Optional.empty());
        List<Consumer<CaseFileItemEvent>> perKey = keyListeners.get(key);
        if (perKey != null) perKey.forEach(l -> l.accept(event));
        anyChangeListeners.forEach(l -> l.accept(event));
    }

    private record ItemEntry(Object value, long version) {}
}
