package io.casehub.control;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CMMN Milestone - represents a significant achievement point in case execution.
 * Milestones have entry criteria (called "achievement criteria" in CMMN) and are
 * automatically achieved when those criteria are satisfied.
 *
 * <p>Milestones are passive markers used to:
 * <ul>
 *   <li>Track progress through a case</li>
 *   <li>Trigger other PlanItems via sentry criteria</li>
 *   <li>Define phase completion</li>
 *   <li>Enable reporting and monitoring</li>
 * </ul>
 *
 * <p>Unlike Stages, Milestones do not contain other items and cannot be manually
 * activated - they are achieved automatically when criteria are met.
 *
 * <p>See CMMN 1.1 specification section 5.4.7 for Milestone semantics.
 */
public class Milestone {
    private String milestoneId;
    private String name;
    private String caseFileId;
    private MilestoneStatus status;
    private Instant createdAt;
    private Instant achievedAt;

    // Achievement criteria - keys that must be present on CaseFile
    private Set<String> achievementCriteria;

    // Optional parent stage
    private Optional<String> parentStageId;

    // Metadata
    private Map<String, Object> metadata;

    /**
     * Lifecycle states for a Milestone.
     */
    public enum MilestoneStatus {
        /** Milestone is defined but achievement criteria not yet satisfied */
        PENDING,

        /** Milestone achievement criteria have been satisfied */
        ACHIEVED
    }

    public Milestone() {
        this.milestoneId = UUID.randomUUID().toString();
        this.status = MilestoneStatus.PENDING;
        this.createdAt = Instant.now();
        this.achievementCriteria = new HashSet<>();
        this.parentStageId = Optional.empty();
        this.metadata = new ConcurrentHashMap<>();
    }

    /**
     * Creates a new Milestone with the given name.
     */
    public static Milestone create(String name) {
        Milestone milestone = new Milestone();
        milestone.name = name;
        return milestone;
    }

    /**
     * Sets the achievement criteria - CaseFile keys that must be present for achievement.
     */
    public Milestone withAchievementCriteria(Set<String> keys) {
        this.achievementCriteria = new HashSet<>(keys);
        return this;
    }

    /**
     * Sets the parent stage for this milestone.
     */
    public Milestone withParentStage(String stageId) {
        this.parentStageId = Optional.of(stageId);
        return this;
    }

    /**
     * Achieves this milestone.
     */
    public void achieve() {
        if (status == MilestoneStatus.PENDING) {
            status = MilestoneStatus.ACHIEVED;
            achievedAt = Instant.now();
        }
    }

    /**
     * Checks if this milestone has been achieved.
     */
    public boolean isAchieved() {
        return status == MilestoneStatus.ACHIEVED;
    }

    /**
     * Checks if this milestone is pending.
     */
    public boolean isPending() {
        return status == MilestoneStatus.PENDING;
    }

    // Getters and setters
    public String getMilestoneId() { return milestoneId; }
    public void setMilestoneId(String milestoneId) { this.milestoneId = milestoneId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCaseFileId() { return caseFileId; }
    public void setCaseFileId(String caseFileId) { this.caseFileId = caseFileId; }

    public MilestoneStatus getStatus() { return status; }
    public void setStatus(MilestoneStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getAchievedAt() { return achievedAt; }
    public void setAchievedAt(Instant achievedAt) { this.achievedAt = achievedAt; }

    public Set<String> getAchievementCriteria() {
        return new HashSet<>(achievementCriteria);
    }
    public void setAchievementCriteria(Set<String> achievementCriteria) {
        this.achievementCriteria = new HashSet<>(achievementCriteria);
    }

    public Optional<String> getParentStageId() { return parentStageId; }
    public void setParentStageId(Optional<String> parentStageId) {
        this.parentStageId = parentStageId;
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
