package io.casehub.resilience;

import io.casehub.coordination.PropagationContext;
import io.casehub.error.ErrorInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A record of a failed PlanItem execution or Task that has exhausted all retries.
 * Preserves the original context (input data, {@code PropagationContext}), complete
 * retry history, and final error -- everything needed to replay the work after fixing
 * the underlying issue.
 *
 * @see DeadLetterQueue
 * @see RetryState.RetryAttempt
 */
public class DeadLetterEntry {
    private String deadLetterId;
    private DeadLetterType type;
    private Instant arrivedAt;
    private String originalId;
    private String caseFileId;
    private String taskType;
    private Map<String, Object> originalContext;
    private PropagationContext propagationContext;
    private ErrorInfo finalError;
    private List<RetryState.RetryAttempt> retryHistory;
    private int totalAttempts;
    private DeadLetterStatus status;

    /** Discriminates whether the dead-lettered work originated from a PlanItem execution or a Task. */
    public enum DeadLetterType { PlanItem, TASK }

    /** Lifecycle status of a dead letter entry, from initial review through resolution. */
    public enum DeadLetterStatus { PENDING_REVIEW, REPLAYED, DISCARDED }

    public DeadLetterEntry() {
        this.deadLetterId = UUID.randomUUID().toString();
        this.arrivedAt = Instant.now();
        this.status = DeadLetterStatus.PENDING_REVIEW;
    }

    // Getters and setters
    public String getDeadLetterId() { return deadLetterId; }
    public void setDeadLetterId(String deadLetterId) { this.deadLetterId = deadLetterId; }
    public DeadLetterType getType() { return type; }
    public void setType(DeadLetterType type) { this.type = type; }
    public Instant getArrivedAt() { return arrivedAt; }
    public String getOriginalId() { return originalId; }
    public void setOriginalId(String originalId) { this.originalId = originalId; }
    public String getCaseFileId() { return caseFileId; }
    public void setCaseFileId(String caseFileId) { this.caseFileId = caseFileId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Map<String, Object> getOriginalContext() { return originalContext; }
    public void setOriginalContext(Map<String, Object> originalContext) { this.originalContext = originalContext; }
    public PropagationContext getPropagationContext() { return propagationContext; }
    public void setPropagationContext(PropagationContext propagationContext) { this.propagationContext = propagationContext; }
    public ErrorInfo getFinalError() { return finalError; }
    public void setFinalError(ErrorInfo finalError) { this.finalError = finalError; }
    public List<RetryState.RetryAttempt> getRetryHistory() { return retryHistory; }
    public void setRetryHistory(List<RetryState.RetryAttempt> retryHistory) { this.retryHistory = retryHistory; }
    public int getTotalAttempts() { return totalAttempts; }
    public void setTotalAttempts(int totalAttempts) { this.totalAttempts = totalAttempts; }
    public DeadLetterStatus getStatus() { return status; }
    public void setStatus(DeadLetterStatus status) { this.status = status; }
}
