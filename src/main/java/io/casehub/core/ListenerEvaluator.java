package io.casehub.core;

import io.casehub.control.CasePlanModel;
import io.casehub.control.Milestone;
import io.casehub.control.PlanItem;
import io.casehub.control.PlanItem.PlanItemStatus;
import io.casehub.control.Stage;
import io.casehub.control.Stage.StageStatus;
import io.casehub.resilience.PoisonPillDetector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates {@link TaskDefinition} entry criteria against the current {@link CaseFile} state and
 * creates {@link PlanItem}s on the {@link CasePlanModel}'s scheduling agenda.
 * Also detects quiescence -- when no further TaskDefinition activations are possible.
 */
@ApplicationScoped
public class ListenerEvaluator {

    private static final Set<PlanItemStatus> ACTIVE_STATUSES =
            Set.of(PlanItemStatus.PENDING, PlanItemStatus.RUNNING, PlanItemStatus.WAITING);

    @Inject
    PoisonPillDetector poisonPillDetector;

    public List<PlanItem> evaluateAndCreatePlanItems(CaseFile domainCaseFile, CasePlanModel casePlanModel,
                                                    List<TaskDefinition> registered,
                                                    String triggerKey) {
        List<PlanItem> created = new ArrayList<>();
        List<PlanItem> agenda = casePlanModel.getAgenda();

        for (TaskDefinition td : registered) {
            // Skip quarantined TaskDefinition
            if (poisonPillDetector.isQuarantined(td.getId())) {
                continue;
            }

            // Check if all entry criteria are present on the domain CaseFile
            boolean allKeysPresent = true;
            for (String key : td.entryCriteria()) {
                if (!domainCaseFile.contains(key)) {
                    allKeysPresent = false;
                    break;
                }
            }
            if (!allKeysPresent) {
                continue;
            }

            // Check custom activation condition
            if (!td.canActivate(domainCaseFile)) {
                continue;
            }

            // Skip TaskDefinition that already has an active PlanItem on the agenda
            boolean hasActivePlanItem = agenda.stream()
                    .anyMatch(planItem -> planItem.getTaskDefinitionId().equals(td.getId())
                            && ACTIVE_STATUSES.contains(planItem.getStatus()));
            if (hasActivePlanItem) {
                continue;
            }

            // Create and add a new PlanItem
            PlanItem planItem = PlanItem.create(td, triggerKey);
            casePlanModel.addPlanItem(planItem);
            created.add(planItem);
        }

        return created;
    }

    public boolean isQuiescent(CasePlanModel casePlanModel) {
        // Check if any PlanItems are active
        boolean hasActivePlanItems = casePlanModel.getAgenda().stream()
                .anyMatch(planItem -> ACTIVE_STATUSES.contains(planItem.getStatus()));

        // Check if any Stages are active
        boolean hasActiveStages = casePlanModel.getActiveStages().size() > 0;

        return !hasActivePlanItems && !hasActiveStages;
    }

    // ---- Stage Evaluation ----

    /**
     * Evaluates stages for activation based on entry criteria.
     * Returns list of stages that were activated.
     */
    public List<Stage> evaluateAndActivateStages(CaseFile domainCaseFile, CasePlanModel casePlanModel) {
        List<Stage> activated = new ArrayList<>();

        for (Stage stage : casePlanModel.getAllStages()) {
            // Skip stages that are not pending
            if (stage.getStatus() != StageStatus.PENDING) {
                continue;
            }

            // Skip stages with manual activation requirement
            if (stage.isManualActivation()) {
                continue;
            }

            // Check if all entry criteria are satisfied
            boolean allCriteriaMet = true;
            for (String key : stage.getEntryCriteria()) {
                if (!domainCaseFile.contains(key)) {
                    allCriteriaMet = false;
                    break;
                }
            }

            if (allCriteriaMet) {
                stage.activate();
                activated.add(stage);
            }
        }

        return activated;
    }

    /**
     * Evaluates stages for completion based on exit criteria and autocomplete logic.
     * Returns list of stages that were completed or terminated.
     */
    public List<Stage> evaluateAndCompleteStages(CaseFile domainCaseFile, CasePlanModel casePlanModel) {
        List<Stage> completed = new ArrayList<>();

        for (Stage stage : casePlanModel.getActiveStages()) {
            // Check exit criteria - triggers termination
            // Only check if exit criteria are actually defined
            if (!stage.getExitCriteria().isEmpty()) {
                boolean exitCriteriaMet = true;
                for (String key : stage.getExitCriteria()) {
                    if (!domainCaseFile.contains(key)) {
                        exitCriteriaMet = false;
                        break;
                    }
                }

                if (exitCriteriaMet) {
                    stage.terminate();
                    completed.add(stage);
                    continue;
                }
            }

            // Check autocomplete - stage completes when all required items complete
            if (stage.isAutocomplete()) {
                // If no required items, autocomplete immediately
                if (stage.getRequiredItems().isEmpty()) {
                    stage.complete();
                    completed.add(stage);
                    continue;
                }

                // Otherwise check if all required items are complete
                boolean allRequiredComplete = true;

                for (String itemId : stage.getRequiredItems()) {
                    // Check if item is a PlanItem
                    boolean planItemComplete = casePlanModel.getAgenda().stream()
                            .filter(pi -> pi.getPlanItemId().equals(itemId))
                            .anyMatch(pi -> pi.getStatus() == PlanItemStatus.COMPLETED);

                    // Check if item is a nested Stage
                    boolean stageComplete = casePlanModel.getStage(itemId)
                            .map(Stage::isTerminal)
                            .orElse(false);

                    if (!planItemComplete && !stageComplete) {
                        allRequiredComplete = false;
                        break;
                    }
                }

                if (allRequiredComplete) {
                    stage.complete();
                    completed.add(stage);
                }
            }
        }

        return completed;
    }

    // ---- Milestone Evaluation ----

    /**
     * Evaluates milestones for achievement based on achievement criteria.
     * Returns list of milestones that were achieved.
     */
    public List<Milestone> evaluateAndAchieveMilestones(CaseFile domainCaseFile, CasePlanModel casePlanModel) {
        List<Milestone> achieved = new ArrayList<>();

        for (Milestone milestone : casePlanModel.getPendingMilestones()) {
            // Check if all achievement criteria are satisfied
            boolean allCriteriaMet = true;
            for (String key : milestone.getAchievementCriteria()) {
                if (!domainCaseFile.contains(key)) {
                    allCriteriaMet = false;
                    break;
                }
            }

            if (allCriteriaMet) {
                milestone.achieve();
                achieved.add(milestone);
            }
        }

        return achieved;
    }

    /**
     * Evaluates PlanItems within a specific stage.
     * Only creates PlanItems for TaskDefinitions if they are contained in active stages.
     */
    public List<PlanItem> evaluateAndCreatePlanItemsInStage(CaseFile domainCaseFile,
                                                             CasePlanModel casePlanModel,
                                                             List<TaskDefinition> registered,
                                                             String stageId,
                                                             String triggerKey) {
        // Get the stage
        Stage stage = casePlanModel.getStage(stageId).orElse(null);
        if (stage == null || !stage.isActive()) {
            return List.of();
        }

        List<PlanItem> created = new ArrayList<>();
        List<PlanItem> agenda = casePlanModel.getAgenda();

        for (TaskDefinition td : registered) {
            // Skip quarantined TaskDefinition
            if (poisonPillDetector.isQuarantined(td.getId())) {
                continue;
            }

            // Check if all entry criteria are present on the domain CaseFile
            boolean allKeysPresent = true;
            for (String key : td.entryCriteria()) {
                if (!domainCaseFile.contains(key)) {
                    allKeysPresent = false;
                    break;
                }
            }
            if (!allKeysPresent) {
                continue;
            }

            // Check custom activation condition
            if (!td.canActivate(domainCaseFile)) {
                continue;
            }

            // Skip TaskDefinition that already has an active PlanItem on the agenda
            boolean hasActivePlanItem = agenda.stream()
                    .anyMatch(planItem -> planItem.getTaskDefinitionId().equals(td.getId())
                            && ACTIVE_STATUSES.contains(planItem.getStatus()));
            if (hasActivePlanItem) {
                continue;
            }

            // Create and add a new PlanItem with stage containment
            PlanItem planItem = PlanItem.create(td, triggerKey);
            planItem.setParentStageId(java.util.Optional.of(stageId));
            casePlanModel.addPlanItem(planItem);
            stage.addPlanItem(planItem.getPlanItemId());
            created.add(planItem);
        }

        return created;
    }
}
