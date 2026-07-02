package io.quarkmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ReadablePanel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Mutable CaseContext backed by a {@code Map<String, Object>} — writable counterpart of
 * {@link MapCaseContext}.
 *
 * <p>Used by {@link TickOrchestratorWorker} to provide a live context that plugins can both
 * read (game state) and write (agent state) during sequential tick orchestration. Writes
 * go into the same backing map so that a later plugin in the chain can see keys set by an
 * earlier plugin.
 *
 * <p>Tracks all mutations (keys set during execution) separately, so the orchestrator can
 * return only the delta as the {@code WorkerResult} output — the engine then applies these
 * mutations to the real CaseContext.
 *
 * <p>Public so that integration tests in other packages (e.g. {@code io.quarkmind.plugin})
 * can construct a writable context for {@code execute(CaseContext)} calls.
 * Use {@link MapCaseContext} for read-only snapshot needs (activation checks).
 */
public final class MutableMapCaseContext implements CaseContext {

    private final Map<String, Object> data;
    private final Map<String, Object> mutations;

    public MutableMapCaseContext(Map<String, Object> initial) {
        this.data = new LinkedHashMap<>(initial);
        this.mutations = new LinkedHashMap<>();
    }

    /** Returns all keys set during execution (the delta). */
    Map<String, Object> mutations() {
        return Map.copyOf(mutations);
    }

    // ── Read operations ─────────────────────────────────────────────────

    @Override public boolean contains(String key) { return data.containsKey(key); }
    @Override public Object  get(String key)       { return data.get(key); }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAs(String key, Class<T> type) {
        Object v = data.get(key);
        return type.isInstance(v) ? (T) v : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        Object v = data.get(key);
        if (v == null) return defaultValue;
        try { return (T) v; } catch (ClassCastException e) { return defaultValue; }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key, Class<T> elementType) {
        Object v = data.get(key);
        if (v instanceof List<?> list) return (List<T>) list;
        return List.of();
    }

    @Override public String  getString(String key)  { return getAs(key, String.class); }
    @Override public Integer getInt(String key)     { return getAs(key, Integer.class); }
    @Override public Long    getLong(String key)    { return getAs(key, Long.class); }
    @Override public Double  getDouble(String key)  { return getAs(key, Double.class); }
    @Override public Boolean getBoolean(String key) { return getAs(key, Boolean.class); }

    @Override public Set<String>         getKeys()  { return Set.copyOf(data.keySet()); }
    @Override public Map<String, Object> getData()  { return Map.copyOf(data); }
    @Override public boolean isEmpty()              { return data.isEmpty(); }
    @Override public int     size()                 { return data.size(); }
    @Override public long    getVersion()           { return 0L; }

    @Override
    public Map<String, Object> getAll(String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String k : keys) { Object v = data.get(k); if (v != null) result.put(k, v); }
        return result;
    }

    // ── Write operations ────────────────────────────────────────────────

    @Override
    public CaseContext set(String key, Object value) {
        data.put(key, value);
        mutations.put(key, value);
        return this;
    }

    @Override
    public CaseContext setAll(Map<String, Object> values) {
        data.putAll(values);
        mutations.putAll(values);
        return this;
    }

    @Override
    public CaseContext remove(String key) {
        data.remove(key);
        mutations.remove(key);  // if we wrote it earlier, undo that mutation
        return this;
    }

    @Override
    public CaseContext clear() {
        data.clear();
        mutations.clear();
        return this;
    }

    @Override
    public CaseContext update(String key, Function<Object, Object> fn) {
        Object newVal = fn.apply(data.get(key));
        return set(key, newVal);
    }

    @Override
    public boolean compareAndSet(String key, Object expected, Object newValue) {
        Object current = data.get(key);
        if (java.util.Objects.equals(current, expected)) {
            set(key, newValue);
            return true;
        }
        return false;
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        Object existing = data.get(key);
        if (existing == null) {
            set(key, value);
            return null;
        }
        return existing;
    }

    @Override
    public Object computeIfAbsent(String key, Function<String, Object> f) {
        Object existing = data.get(key);
        if (existing == null) {
            Object computed = f.apply(key);
            set(key, computed);
            return computed;
        }
        return existing;
    }

    // ── Unsupported structural operations ─────────────────────────────

    @Override public ReadablePanel              panel(String name)                    { throw new UnsupportedOperationException("MutableMapCaseContext does not support panels"); }
    @Override public Object                     getPath(String path)                  { throw new UnsupportedOperationException("MutableMapCaseContext does not support path access"); }
    @Override public String                     getPathAsString(String path)          { throw new UnsupportedOperationException("MutableMapCaseContext does not support path access"); }
    @Override public CaseContext                setPath(String path, Object value)    { throw new UnsupportedOperationException("MutableMapCaseContext does not support path access"); }
    @Override public Optional<JsonNode>         applyAndDiff(String path, Object v)   { throw new UnsupportedOperationException("MutableMapCaseContext does not support diff"); }
    @Override public JsonNode                   asJsonNode()                           { throw new UnsupportedOperationException("MutableMapCaseContext does not support JSON serialisation"); }
    @Override public CaseContext                merge(CaseContext other)               { throw new UnsupportedOperationException("MutableMapCaseContext does not support merge"); }
    @Override public CaseContext                snapshot()                             { throw new UnsupportedOperationException("MutableMapCaseContext does not support snapshot"); }
    @Override public JsonNode                   diff(CaseContext other)                { throw new UnsupportedOperationException("MutableMapCaseContext does not support diff"); }
    @Override public void                       applyDiff(JsonNode diff)               { throw new UnsupportedOperationException("MutableMapCaseContext does not support diff"); }
}
