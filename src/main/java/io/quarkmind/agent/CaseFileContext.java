package io.quarkmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ReadablePanel;
import io.casehub.core.CaseFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 1 migration bridge — removed in Phase 2 when poc {@link CaseFile} is dropped.
 *
 * <p>Wraps a poc {@link CaseFile} as a {@link CaseContext} so that plugin implementations
 * written against the new API can be called from the poc engine's dispatch loop via the
 * bridge in each plugin's {@code execute(CaseFile)} method.
 *
 * <p>Only the methods actually used by QuarkMind plugins are implemented. Others throw
 * {@link UnsupportedOperationException} to surface incorrect usage early.
 *
 * <p>Refs #193
 */
public final class CaseFileContext implements CaseContext {

    private final Map<String, Object> data;

    public CaseFileContext(CaseFile caseFile) {
        this.data = new LinkedHashMap<>();
        for (String key : QuarkMindCaseFile.ALL_KEYS) {
            caseFile.get(key, Object.class).ifPresent(v -> data.put(key, v));
        }
    }

    /** Used by output-syncing bridge to retrieve values written during execute(). */
    public Map<String, Object> toMap() { return Map.copyOf(data); }

    @Override
    public Object get(String key) { return data.get(key); }

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
    public boolean contains(String key) { return data.containsKey(key); }

    @Override
    public CaseContext set(String key, Object value) {
        if (value != null) data.put(key, value);
        return this;
    }

    @Override
    public CaseContext setAll(Map<String, Object> values) {
        values.forEach((k, v) -> { if (v != null) data.put(k, v); });
        return this;
    }

    @Override public Map<String, Object> getData() { return Map.copyOf(data); }
    @Override public Set<String> getKeys() { return Set.copyOf(data.keySet()); }
    @Override public boolean isEmpty() { return data.isEmpty(); }
    @Override public int size() { return data.size(); }

    // ── Unused in Phase 1 — throw to surface incorrect usage ────────────────

    @Override public ReadablePanel panel(String name) { throw new UnsupportedOperationException("CaseFileContext.panel() — Phase 1 bridge only"); }
    @Override public Object computeIfAbsent(String key, Function<String, Object> f) { throw new UnsupportedOperationException(); }
    @Override public Object putIfAbsent(String key, Object value) { throw new UnsupportedOperationException(); }
    @Override public boolean compareAndSet(String key, Object expected, Object newValue) { throw new UnsupportedOperationException(); }
    @Override public CaseContext update(String key, Function<Object, Object> fn) { throw new UnsupportedOperationException(); }
    @Override public String getString(String key) { return getAs(key, String.class); }
    @Override public Integer getInt(String key) { return getAs(key, Integer.class); }
    @Override public Long getLong(String key) { return getAs(key, Long.class); }
    @Override public Double getDouble(String key) { return getAs(key, Double.class); }
    @Override public Boolean getBoolean(String key) { return getAs(key, Boolean.class); }
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key, Class<T> elementType) {
        Object v = data.get(key);
        if (v instanceof List<?> list) return (List<T>) list;
        return List.of();
    }
    @Override public Object getPath(String path) { throw new UnsupportedOperationException(); }
    @Override public String getPathAsString(String path) { throw new UnsupportedOperationException(); }
    @Override public CaseContext setPath(String path, Object value) { throw new UnsupportedOperationException(); }
    @Override public java.util.Optional<JsonNode> applyAndDiff(String path, Object value) { throw new UnsupportedOperationException(); }
    @Override public CaseContext remove(String key) { throw new UnsupportedOperationException("CaseFileContext.remove() — Phase 1 bridge only; use produces() sync for output, not removal"); }
    @Override public CaseContext clear() { throw new UnsupportedOperationException("CaseFileContext.clear() — Phase 1 bridge only"); }
    @Override public JsonNode asJsonNode() { throw new UnsupportedOperationException(); }
    @Override public CaseContext merge(CaseContext other) { throw new UnsupportedOperationException(); }
    @Override public CaseContext snapshot() { throw new UnsupportedOperationException("CaseFileContext.snapshot() not supported"); }
    @Override public JsonNode diff(CaseContext other) { throw new UnsupportedOperationException(); }
    @Override public void applyDiff(JsonNode diff) { throw new UnsupportedOperationException(); }
    @Override public long getVersion() { return 0; }
    @Override public Map<String, Object> getAll(String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String k : keys) { Object v = data.get(k); if (v != null) result.put(k, v); }
        return result;
    }
}
