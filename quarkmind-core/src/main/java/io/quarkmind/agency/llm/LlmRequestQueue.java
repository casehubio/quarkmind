package io.quarkmind.agency.llm;

public interface LlmRequestQueue {
    void submit(LlmRequest request);
    int pendingCount();
    boolean hasCapacity();
}
