package io.casehub.worker;

import io.casehub.error.ErrorInfo;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The result of a completed or failed {@link Task}. Carries output data on success or
 * structured {@link io.casehub.error.ErrorInfo} on failure, along with the completing
 * worker ID and timestamp. Provides factory methods {@link #success} and {@link #failure}
 * for the two primary outcomes.
 *
 * @see Task
 * @see TaskStatus
 */
public class TaskResult {
    private String taskId;
    private TaskStatus status;
    private Map<String, Object> data;
    private Optional<ErrorInfo> error;
    private Instant completedAt;
    private Optional<String> workerId;

    public TaskResult() {
        this.data = new HashMap<>();
        this.error = Optional.empty();
        this.completedAt = Instant.now();
        this.workerId = Optional.empty();
    }

    public TaskResult(String taskId, TaskStatus status, Map<String, Object> data) {
        this();
        this.taskId = taskId;
        this.status = status;
        this.data = data;
    }

    public static TaskResult success(String taskId, Map<String, Object> data) {
        TaskResult result = new TaskResult(taskId, TaskStatus.COMPLETED, data);
        return result;
    }

    public static TaskResult failure(String taskId, ErrorInfo error) {
        TaskResult result = new TaskResult();
        result.taskId = taskId;
        result.status = TaskStatus.FAULTED;
        result.error = Optional.of(error);
        return result;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public Optional<ErrorInfo> getError() { return error; }
    public void setError(Optional<ErrorInfo> error) { this.error = error; }
    public Instant getCompletedAt() { return completedAt; }
    public Optional<String> getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = Optional.ofNullable(workerId); }
}
