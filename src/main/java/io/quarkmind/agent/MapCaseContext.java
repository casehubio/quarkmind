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
 * CaseContext backed by a Map&lt;String, Object&gt; snapshot.
 *
 * <p>Used for pre-engine activation evaluation in PluginDispatchBroker — wraps the
 * immutable caseData map produced by GameStateTranslator without touching the database.
 * Also simplifies unit tests: replaces the CaseFileContext + InMemoryCaseFileRepository
 * construction pattern with {@code new MapCaseContext(Map.of(...))}.
 *
 * <p>Only read operations are implemented; write operations throw
 * {@link UnsupportedOperationException}. activateIf() predicates must use only
 * {@code contains()} and {@code get()} from this context (or CDI singletons).
 */
public final class MapCaseContext implements CaseContext {

    private final Map<String, Object> data;

    public MapCaseContext(Map<String, Object> data) {
        this.data = Map.copyOf(data);   // immutable snapshot
    }

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

    // ── Write operations — not supported on a snapshot ────────────────────────

    @Override public CaseContext set(String key, Object value)                      { throw new UnsupportedOperationException("MapCaseContext is read-only"); }
    @Override public CaseContext setAll(Map<String, Object> values)                 { throw new UnsupportedOperationException("MapCaseContext is read-only"); }
    @Override public CaseContext remove(String key)                                  { throw new UnsupportedOperationException("MapCaseContext is read-only"); }
    @Override public CaseContext clear()                                              { throw new UnsupportedOperationException("MapCaseContext is read-only"); }
    @Override public CaseContext update(String key, Function<Object, Object> fn)    { throw new UnsupportedOperationException("MapCaseContext is read-only"); }
    @Override public boolean compareAndSet(String key, Object expected, Object nv)  { throw new UnsupportedOperationException("MapCaseContext is read-only"); }
    @Override public Object putIfAbsent(String key, Object value)                   { throw new UnsupportedOperationException("MapCaseContext is read-only"); }
    @Override public Object computeIfAbsent(String key, Function<String, Object> f) { throw new UnsupportedOperationException("MapCaseContext is read-only"); }

    // ── Unsupported structural operations ──────────────────────────────────────

    @Override public ReadablePanel              panel(String name)                    { throw new UnsupportedOperationException(); }
    @Override public Object                     getPath(String path)                  { throw new UnsupportedOperationException(); }
    @Override public String                     getPathAsString(String path)          { throw new UnsupportedOperationException(); }
    @Override public CaseContext                setPath(String path, Object value)    { throw new UnsupportedOperationException(); }
    @Override public Optional<JsonNode>         applyAndDiff(String path, Object v)   { throw new UnsupportedOperationException(); }
    @Override public JsonNode                   asJsonNode()                           { throw new UnsupportedOperationException(); }
    @Override public CaseContext                merge(CaseContext other)               { throw new UnsupportedOperationException(); }
    @Override public CaseContext                snapshot()                             { throw new UnsupportedOperationException(); }
    @Override public JsonNode                   diff(CaseContext other)                { throw new UnsupportedOperationException(); }
    @Override public void                       applyDiff(JsonNode diff)               { throw new UnsupportedOperationException(); }
}
