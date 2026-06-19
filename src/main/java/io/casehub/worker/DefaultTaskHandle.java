package io.casehub.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Default implementation of {@link TaskHandle} that wraps a submitted {@link Task} and
 * its owning {@link TaskRepository}. Provides live status lookups, async result completion
 * via a {@link CompletableFuture}, and cancellation support. The {@link #completeWith(TaskResult)}
 * method is used internally by the task execution pipeline to resolve the result future.
 *
 * @see TaskHandle
 * @see TaskBroker
 */
public class DefaultTaskHandle implements TaskHandle {

    private final Task task;
    private final TaskRepository taskRepository;
    private final CompletableFuture<TaskResult> resultFuture;

    public DefaultTaskHandle(Task task, TaskRepository taskRepository) {
        this.task = task;
        this.taskRepository = taskRepository;
        this.resultFuture = new CompletableFuture<>();
    }

    @Override
    public String getTaskId() {
        return task.getId().toString();
    }

    @Override
    public TaskStatus getStatus() {
        return taskRepository.findById(task.getId())
                .map(Task::getStatus)
                .orElse(task.getStatus());
    }

    @Override
    public CompletableFuture<TaskResult> getResultAsync() {
        return resultFuture;
    }

    @Override
    public TaskResult awaitResult(Duration timeout) throws TimeoutException, InterruptedException {
        try {
            return resultFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public boolean cancel() {
        TaskStatus current = getStatus();
        if (isTerminal(current)) {
            return false;
        }
        task.setStatus(TaskStatus.CANCELLED);
        taskRepository.save(task);
        return true;
    }

    @Override
    public boolean isCancelled() {
        return getStatus() == TaskStatus.CANCELLED;
    }

    @Override
    public Instant getSubmittedAt() {
        return task.getSubmittedAt();
    }

    @Override
    public Optional<String> getAssignedWorker() {
        return task.getAssignedWorkerId();
    }

    /**
     * Completes the result future with the given {@link TaskResult}, unblocking any
     * callers waiting on {@link #getResultAsync()} or {@link #awaitResult(Duration)}.
     *
     * @param result the task result to complete with
     */
    public void completeWith(TaskResult result) {
        resultFuture.complete(result);
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAULTED
                || status == TaskStatus.CANCELLED;
    }
}
