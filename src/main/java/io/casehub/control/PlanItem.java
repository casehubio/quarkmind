package io.casehub.control;

import io.casehub.core.TaskDefinition;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a pending, prioritized activation of a {@link TaskDefinition} on the
 * {@link CasePlanModel}'s scheduling agenda. Carries the eligible TaskDefinition identifier,
 * assigned priority, trigger key, and lifecycle status. Implements {@link Comparable} for
 * priority-ordered scheduling (higher priority first, FIFO for equal priority).
 * See design document section 6.3.
 */
public class PlanItem implements Comparable<PlanItem> {
    private String planItemId;
    private String taskDefinitionId;
    private int priority;
    private String triggerKey;
    private Instant createdAt;
    private Optional<String> focusArea;
    private Optional<String> parentStageId;
    private PlanItemStatus status;

    /** Lifecycle status of a {@link PlanItem}, aligned with CNCF Serverless Workflow (OWL) phases. */
    public enum PlanItemStatus {
        PENDING,
        RUNNING,
        WAITING,
        SUSPENDED,
        COMPLETED,
        FAULTED,
        CANCELLED
    }

    public PlanItem() {
        this.planItemId = UUID.randomUUID().toString();
        this.priority = 0;
        this.createdAt = Instant.now();
        this.focusArea = Optional.empty();
        this.parentStageId = Optional.empty();
        this.status = PlanItemStatus.PENDING;
    }

    public static PlanItem create(TaskDefinition td, String triggerKey) {
        PlanItem planItem = new PlanItem();
        planItem.taskDefinitionId = td.getId();
        planItem.triggerKey = triggerKey;
        return planItem;
    }

    @Override
    public int compareTo(PlanItem other) {
        // Higher priority first
        int cmp = Integer.compare(other.priority, this.priority);
        if (cmp != 0) return cmp;
        // Earlier creation time first (FIFO for equal priority)
        return this.createdAt.compareTo(other.createdAt);
    }

    // Getters and setters
    public String getPlanItemId() { return planItemId; }
    public void setPlanItemId(String planItemId) { this.planItemId = planItemId; }
    public String getTaskDefinitionId() { return taskDefinitionId; }
    public void setTaskDefinitionId(String taskDefinitionId) { this.taskDefinitionId = taskDefinitionId; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getTriggerKey() { return triggerKey; }
    public void setTriggerKey(String triggerKey) { this.triggerKey = triggerKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Optional<String> getFocusArea() { return focusArea; }
    public void setFocusArea(Optional<String> focusArea) { this.focusArea = focusArea; }
    public Optional<String> getParentStageId() { return parentStageId; }
    public void setParentStageId(Optional<String> parentStageId) { this.parentStageId = parentStageId; }
    public PlanItemStatus getStatus() { return status; }
    public void setStatus(PlanItemStatus status) { this.status = status; }
}
