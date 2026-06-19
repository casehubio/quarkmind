package io.casehub.control;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CMMN Stage - a container for {@link PlanItem}s and other Stages that can be activated
 * and completed as a unit. Stages have entry and exit criteria and support hierarchical
 * nesting. When a stage is activated, its entry criteria must be satisfied. When exit
 * criteria are met, the stage completes and all contained items complete.
 *
 * <p>Stages support:
 * <ul>
 *   <li>Entry criteria - conditions that must be met for stage activation</li>
 *   <li>Exit criteria - conditions that trigger stage completion</li>
 *   <li>Containment - PlanItems and nested Stages</li>
 *   <li>Autocomplete - stage completes when all required items complete</li>
 *   <li>Manual activation - can require explicit activation even when criteria are met</li>
 * </ul>
 *
 * <p>See CMMN 1.1 specification section 5.4.4 for Stage semantics.
 */
public class Stage {
    private String stageId;
    private String name;
    private String caseFileId;
    private StageStatus status;
    private Instant createdAt;
    private Instant activatedAt;
    private Instant completedAt;

    // Criteria - keys that must be present on CaseFile
    private Set<String> entryCriteria;
    private Set<String> exitCriteria;

    // Containment
    private Optional<String> parentStageId;
    private List<String> containedPlanItemIds;
    private List<String> containedStageIds;
    private List<String> requiredItems; // Items that must complete for autocomplete

    // Behavior
    private boolean manualActivation;
    private boolean autocomplete;

    // Metadata
    private Map<String, Object> metadata;

    /**
     * Lifecycle states for a Stage, aligned with CMMN Stage lifecycle.
     */
    public enum StageStatus {
        /** Stage is defined but entry criteria not yet satisfied */
        PENDING,

        /** Stage is active and contained items can execute */
        ACTIVE,

        /** Stage is suspended (paused) */
        SUSPENDED,

        /** Stage completed successfully */
        COMPLETED,

        /** Stage terminated due to exit criteria */
        TERMINATED,

        /** Stage failed */
        FAULTED
    }

    public Stage() {
        this.stageId = UUID.randomUUID().toString();
        this.status = StageStatus.PENDING;
        this.createdAt = Instant.now();
        this.entryCriteria = new HashSet<>();
        this.exitCriteria = new HashSet<>();
        this.parentStageId = Optional.empty();
        this.containedPlanItemIds = new CopyOnWriteArrayList<>();
        this.containedStageIds = new CopyOnWriteArrayList<>();
        this.requiredItems = new CopyOnWriteArrayList<>();
        this.manualActivation = false;
        this.autocomplete = true;
        this.metadata = new ConcurrentHashMap<>();
    }

    /**
     * Creates a new Stage with the given name.
     */
    public static Stage create(String name) {
        Stage stage = new Stage();
        stage.name = name;
        return stage;
    }

    /**
     * Sets the entry criteria - CaseFile keys that must be present for activation.
     */
    public Stage withEntryCriteria(Set<String> keys) {
        this.entryCriteria = new HashSet<>(keys);
        return this;
    }

    /**
     * Sets the exit criteria - CaseFile keys that trigger completion.
     */
    public Stage withExitCriteria(Set<String> keys) {
        this.exitCriteria = new HashSet<>(keys);
        return this;
    }

    /**
     * Sets whether this stage requires manual activation even when criteria are met.
     */
    public Stage withManualActivation(boolean manual) {
        this.manualActivation = manual;
        return this;
    }

    /**
     * Sets whether this stage autocompletes when all required items complete.
     */
    public Stage withAutocomplete(boolean autocomplete) {
        this.autocomplete = autocomplete;
        return this;
    }

    /**
     * Adds a PlanItem to this stage's containment.
     */
    public void addPlanItem(String planItemId) {
        if (!containedPlanItemIds.contains(planItemId)) {
            containedPlanItemIds.add(planItemId);
        }
    }

    /**
     * Adds a nested Stage to this stage's containment.
     */
    public void addNestedStage(String stageId) {
        if (!containedStageIds.contains(stageId)) {
            containedStageIds.add(stageId);
        }
    }

    /**
     * Marks an item as required for autocomplete.
     */
    public void addRequiredItem(String itemId) {
        if (!requiredItems.contains(itemId)) {
            requiredItems.add(itemId);
        }
    }

    /**
     * Removes a PlanItem from containment.
     */
    public void removePlanItem(String planItemId) {
        containedPlanItemIds.remove(planItemId);
        requiredItems.remove(planItemId);
    }

    /**
     * Removes a nested Stage from containment.
     */
    public void removeNestedStage(String stageId) {
        containedStageIds.remove(stageId);
        requiredItems.remove(stageId);
    }

    /**
     * Activates this stage.
     */
    public void activate() {
        if (status == StageStatus.PENDING) {
            status = StageStatus.ACTIVE;
            activatedAt = Instant.now();
        }
    }

    /**
     * Completes this stage.
     */
    public void complete() {
        if (status == StageStatus.ACTIVE || status == StageStatus.SUSPENDED) {
            status = StageStatus.COMPLETED;
            completedAt = Instant.now();
        }
    }

    /**
     * Terminates this stage (exit criteria triggered).
     */
    public void terminate() {
        if (status == StageStatus.ACTIVE || status == StageStatus.SUSPENDED) {
            status = StageStatus.TERMINATED;
            completedAt = Instant.now();
        }
    }

    /**
     * Suspends this stage.
     */
    public void suspend() {
        if (status == StageStatus.ACTIVE) {
            status = StageStatus.SUSPENDED;
        }
    }

    /**
     * Resumes this stage from suspension.
     */
    public void resume() {
        if (status == StageStatus.SUSPENDED) {
            status = StageStatus.ACTIVE;
        }
    }

    /**
     * Marks this stage as faulted.
     */
    public void fault() {
        status = StageStatus.FAULTED;
        completedAt = Instant.now();
    }

    /**
     * Checks if this stage is in a terminal state.
     */
    public boolean isTerminal() {
        return status == StageStatus.COMPLETED ||
               status == StageStatus.TERMINATED ||
               status == StageStatus.FAULTED;
    }

    /**
     * Checks if this stage is active.
     */
    public boolean isActive() {
        return status == StageStatus.ACTIVE;
    }

    // Getters and setters
    public String getStageId() { return stageId; }
    public void setStageId(String stageId) { this.stageId = stageId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCaseFileId() { return caseFileId; }
    public void setCaseFileId(String caseFileId) { this.caseFileId = caseFileId; }

    public StageStatus getStatus() { return status; }
    public void setStatus(StageStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Set<String> getEntryCriteria() { return new HashSet<>(entryCriteria); }
    public void setEntryCriteria(Set<String> entryCriteria) {
        this.entryCriteria = new HashSet<>(entryCriteria);
    }

    public Set<String> getExitCriteria() { return new HashSet<>(exitCriteria); }
    public void setExitCriteria(Set<String> exitCriteria) {
        this.exitCriteria = new HashSet<>(exitCriteria);
    }

    public Optional<String> getParentStageId() { return parentStageId; }
    public void setParentStageId(Optional<String> parentStageId) {
        this.parentStageId = parentStageId;
    }

    public List<String> getContainedPlanItemIds() {
        return new ArrayList<>(containedPlanItemIds);
    }
    public void setContainedPlanItemIds(List<String> ids) {
        this.containedPlanItemIds = new CopyOnWriteArrayList<>(ids);
    }

    public List<String> getContainedStageIds() {
        return new ArrayList<>(containedStageIds);
    }
    public void setContainedStageIds(List<String> ids) {
        this.containedStageIds = new CopyOnWriteArrayList<>(ids);
    }

    public List<String> getRequiredItems() {
        return new ArrayList<>(requiredItems);
    }
    public void setRequiredItems(List<String> ids) {
        this.requiredItems = new CopyOnWriteArrayList<>(ids);
    }

    public boolean isManualActivation() { return manualActivation; }
    public void setManualActivation(boolean manualActivation) {
        this.manualActivation = manualActivation;
    }

    public boolean isAutocomplete() { return autocomplete; }
    public void setAutocomplete(boolean autocomplete) {
        this.autocomplete = autocomplete;
    }

    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = new ConcurrentHashMap<>(metadata);
    }

    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getMetadata(String key, Class<T> type) {
        Object value = metadata.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }
}
