package io.casehub.control;

import io.casehub.core.CaseFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A dedicated CaseHub for control reasoning, paired 1:1 with each domain {@link CaseFile} instance,
 * following the architecture described by Hayes-Roth (1985). Holds the scheduling agenda (prioritized
 * {@link PlanItem}s), current focus of attention, strategy, resource budget, and extensible key-value state.
 * Written to by {@link PlanningStrategy}s and read by the CaseEngine to determine which
 * PlanItems to execute next. See design document section 6.1.
 */
public interface CasePlanModel {
    String getCasePlanModelId();
    CaseFile getDomainCaseFile();

    // Scheduling Agenda — prioritized PlanItems
    void addPlanItem(PlanItem planItem);
    void removePlanItem(String planItemId);
    List<PlanItem> getAgenda();
    List<PlanItem> getTopPlanItems(int maxCount);
    void clearAgenda();

    // Focus of Attention
    void setFocus(String focusArea);
    Optional<String> getFocus();
    void setFocusRationale(String rationale);

    // Strategy
    void setStrategy(String strategy);
    Optional<String> getStrategy();

    // Resource Tracking
    void setResourceBudget(Map<String, Object> budget);
    Map<String, Object> getResourceBudget();

    // General key-value (extensible by custom PlanningStrategy)
    void put(String key, Object value);
    <T> Optional<T> get(String key, Class<T> type);
    Map<String, Object> snapshot();

    // Stage Management
    void addStage(Stage stage);
    void removeStage(String stageId);
    Optional<Stage> getStage(String stageId);
    List<Stage> getAllStages();
    List<Stage> getActiveStages();
    List<Stage> getRootStages(); // Stages with no parent

    // Milestone Management
    void addMilestone(Milestone milestone);
    void removeMilestone(String milestoneId);
    Optional<Milestone> getMilestone(String milestoneId);
    List<Milestone> getAllMilestones();
    List<Milestone> getPendingMilestones();
    List<Milestone> getAchievedMilestones();
}
