package io.quarkmind.agency.moment;

import java.util.Map;

public record MomentEvent(String type, long tick, Map<String, Object> data) {
    public MomentEvent {
        data = data != null ? Map.copyOf(data) : Map.of();
    }
}
