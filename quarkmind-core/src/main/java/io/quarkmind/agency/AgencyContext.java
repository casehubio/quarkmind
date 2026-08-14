package io.quarkmind.agency;

import io.quarkmind.agency.needs.NeedState;
import java.util.HashMap;
import java.util.Map;

public class AgencyContext {

    private final NeedState needState;
    private long tick;
    private final Map<String, Object> state = new HashMap<>();

    public AgencyContext(NeedState needState) {
        this.needState = needState;
    }

    public NeedState needState() { return needState; }

    public long tick() { return tick; }

    public void setTick(long tick) { this.tick = tick; }

    public void put(String key, Object value) { state.put(key, value); }

    public Object get(String key) { return state.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T getAs(String key, Class<T> type) {
        Object v = state.get(key);
        return type.isInstance(v) ? (T) v : null;
    }

    public boolean contains(String key) { return state.containsKey(key); }
}
