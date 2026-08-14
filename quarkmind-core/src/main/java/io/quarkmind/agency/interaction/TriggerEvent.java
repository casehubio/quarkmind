package io.quarkmind.agency.interaction;

import java.util.Map;

public record TriggerEvent(String type, Map<String, Object> data) {
    public TriggerEvent {
        data = data != null ? Map.copyOf(data) : Map.of();
    }
}
