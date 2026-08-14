package io.quarkmind.agency.llm;

import java.util.Map;

public record LlmRequest(String prompt, LlmPriority priority, Map<String, Object> metadata) {
    public LlmRequest {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
