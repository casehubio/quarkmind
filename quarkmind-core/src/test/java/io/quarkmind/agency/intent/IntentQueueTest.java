package io.quarkmind.agency.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentQueueTest {

    record TestIntent(String action) implements Intent {}

    @Test
    void enqueueAndDequeue() {
        var queue = new IntentQueue<TestIntent>();
        queue.enqueue(new TestIntent("move"));
        queue.enqueue(new TestIntent("attack"));

        assertEquals("move", queue.dequeue().action());
        assertEquals("attack", queue.dequeue().action());
        assertNull(queue.dequeue());
    }

    @Test
    void drainAllClearsQueue() {
        var queue = new IntentQueue<TestIntent>();
        queue.enqueue(new TestIntent("a"));
        queue.enqueue(new TestIntent("b"));

        var drained = queue.drainAll();
        assertEquals(2, drained.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void sizeTracksItems() {
        var queue = new IntentQueue<TestIntent>();
        assertEquals(0, queue.size());
        queue.enqueue(new TestIntent("x"));
        assertEquals(1, queue.size());
    }
}
