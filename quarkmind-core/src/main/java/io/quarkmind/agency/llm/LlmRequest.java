package io.quarkmind.agency.llm;

import java.util.Map;
import java.util.function.Consumer;

public record LlmRequest(String prompt, LlmPriority priority,
                          Map<String, Object> metadata,
                          Consumer<String> responseHandler) {
    public LlmRequest(String prompt, LlmPriority priority, Map<String, Object> metadata) {
        this(prompt, priority, metadata, null);
    }
    public LlmRequest {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
