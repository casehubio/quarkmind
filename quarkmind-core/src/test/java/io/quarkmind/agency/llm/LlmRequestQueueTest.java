package io.quarkmind.agency.llm;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmRequestQueueTest {

    @Test
    void llmPriority_ordering() {
        assertTrue(LlmPriority.URGENT.ordinal() > LlmPriority.HIGH.ordinal());
        assertTrue(LlmPriority.HIGH.ordinal() > LlmPriority.NORMAL.ordinal());
        assertTrue(LlmPriority.NORMAL.ordinal() > LlmPriority.LOW.ordinal());
    }

    @Test
    void llmRequest_isImmutable() {
        var request = new LlmRequest("analyse position", LlmPriority.HIGH, Map.of("role", "advisor"));
        assertEquals("analyse position", request.prompt());
        assertEquals(LlmPriority.HIGH, request.priority());
        assertThrows(UnsupportedOperationException.class, () -> request.metadata().put("x", "y"));
    }

    @Test
    void llmRequest_handlesNullMetadata() {
        var request = new LlmRequest("prompt", LlmPriority.NORMAL, null);
        assertNotNull(request.metadata());
        assertTrue(request.metadata().isEmpty());
    }

    @Test
    void queue_interface_isImplementable() {
        LlmRequestQueue queue = new LlmRequestQueue() {
            private int count = 0;
            @Override public void submit(LlmRequest request) { count++; }
            @Override public int pendingCount() { return count; }
            @Override public boolean hasCapacity() { return count < 10; }
        };

        assertTrue(queue.hasCapacity());
        assertEquals(0, queue.pendingCount());

        queue.submit(new LlmRequest("test", LlmPriority.NORMAL, Map.of()));
        assertEquals(1, queue.pendingCount());
        assertTrue(queue.hasCapacity());
    }
}
