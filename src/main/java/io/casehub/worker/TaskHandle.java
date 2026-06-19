package io.casehub.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Opaque handle returned to requestors upon task submission. Provides non-blocking status
 * checks, async and blocking result retrieval, and cancellation. This is the requestor's
 * primary interface for tracking submitted work.
 *
 * @see TaskBroker#submitTask(TaskRequest)
 */
public interface TaskHandle {
    String getTaskId();
    TaskStatus getStatus();
    CompletableFuture<TaskResult> getResultAsync();
    TaskResult awaitResult(Duration timeout) throws TimeoutException, InterruptedException;
    boolean cancel();
    boolean isCancelled();
    Instant getSubmittedAt();
    Optional<String> getAssignedWorker();
}
