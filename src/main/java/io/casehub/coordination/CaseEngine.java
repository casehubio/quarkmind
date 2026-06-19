package io.casehub.coordination;

import io.casehub.control.CasePlanModel;
import io.casehub.control.DefaultCasePlanModel;
import io.casehub.control.PlanItem;
import io.casehub.control.PlanItem.PlanItemStatus;
import io.casehub.control.PlanningStrategy;
import io.casehub.control.PlanningStrategy.ControlActivationCondition;
import io.casehub.core.CaseFile;
import io.casehub.core.CaseStatus;
import io.casehub.core.ListenerEvaluator;
import io.casehub.core.TaskDefinition;
import io.casehub.core.TaskDefinitionRegistry;
import io.casehub.core.spi.CaseFileRepository;
import io.casehub.error.CaseCreationException;
import io.casehub.resilience.PoisonPillDetector;
import io.casehub.worker.NotificationService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The execution engine for the CaseHub control loop. Creates CaseFiles via
 * {@link CaseFileRepository}, pairs them with CasePlanModels, then runs the
 * control loop: evaluate entry criteria → create PlanItems → invoke
 * PlanningStrategies → execute top PlanItem → repeat until complete or quiescent.
 *
 * Child CaseFiles are created with parent references set via the repository,
 * forming the POJO object graph. Lineage traversal is done directly via
 * {@link CaseFile#getParentCase()} and {@link CaseFile#getChildCases()}.
 */
@ApplicationScoped
public class CaseEngine {

    private static final Logger LOG = Logger.getLogger(CaseEngine.class);

    @Inject CaseFileRepository caseFileRepository;
    @Inject TaskDefinitionRegistry taskDefRegistry;
    @Inject ListenerEvaluator listenerEvaluator;
    @Inject NotificationService notificationService;
    @Inject PoisonPillDetector poisonPillDetector;

    private final Map<Long, CaseFile> activeCaseFiles = new ConcurrentHashMap<>();
    private final Map<Long, CasePlanModel> casePlanModels = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<CaseFile>> caseFileFutures = new ConcurrentHashMap<>();
    private final ExecutorService controlLoopExecutor = Executors.newCachedThreadPool();

    public CaseFile createAndSolve(String caseType, Map<String, Object> initialState)
            throws CaseCreationException {
        PropagationContext ctx = PropagationContext.createRoot();
        CaseFile caseFile = caseFileRepository.create(caseType, initialState, ctx);
        scheduleControlLoop(caseFile);
        return caseFile;
    }

    public CaseFile createAndSolve(String caseType, Map<String, Object> initialState,
                                    Duration timeout) throws CaseCreationException {
        PropagationContext ctx = PropagationContext.createRoot(Map.of(), timeout);
        CaseFile caseFile = caseFileRepository.create(caseType, initialState, ctx);
        scheduleControlLoop(caseFile);
        return caseFile;
    }

    public CaseFile createChildCaseFile(CaseFile parent, String caseType,
                                         Map<String, Object> initialState) {
        CaseFile child = caseFileRepository.createChild(caseType, initialState, parent);
        scheduleControlLoop(child);
        return child;
    }

    private void scheduleControlLoop(CaseFile caseFile) {
        Long id = caseFile.getId();
        activeCaseFiles.put(id, caseFile);
        casePlanModels.put(id, new DefaultCasePlanModel(caseFile));
        caseFileFutures.put(id, new CompletableFuture<>());
        controlLoopExecutor.submit(() -> runControlLoop(caseFile));
    }

    private void runControlLoop(CaseFile caseFile) {
        Long id = caseFile.getId();
        CasePlanModel casePlanModel = casePlanModels.get(id);

        caseFile.setStatus(CaseStatus.RUNNING);

        List<TaskDefinition> taskDefs = taskDefRegistry.getForCaseType(caseFile.getCaseType());
        List<PlanningStrategy> strategies = taskDefRegistry.getStrategiesForCaseType(caseFile.getCaseType());

        List<PlanItem> newPlanItems = listenerEvaluator.evaluateAndCreatePlanItems(
                caseFile, casePlanModel, taskDefs, null);

        while (caseFile.getStatus() == CaseStatus.RUNNING) {
            for (PlanningStrategy strategy : strategies) {
                ControlActivationCondition condition = strategy.getActivationCondition();
                if (condition == ControlActivationCondition.ON_NEW_PLAN_ITEMS && !newPlanItems.isEmpty()) {
                    strategy.reason(casePlanModel, caseFile);
                } else if (condition == ControlActivationCondition.ALWAYS) {
                    strategy.reason(casePlanModel, caseFile);
                }
            }

            List<PlanItem> topPlanItems = casePlanModel.getTopPlanItems(1);

            if (topPlanItems.isEmpty()) {
                if (listenerEvaluator.isQuiescent(casePlanModel)) {
                    caseFile.setStatus(CaseStatus.WAITING);
                    break;
                }
            }

            newPlanItems = List.of();

            for (PlanItem planItem : topPlanItems) {
                planItem.setStatus(PlanItemStatus.RUNNING);

                var tdOpt = taskDefRegistry.getById(planItem.getTaskDefinitionId());
                if (tdOpt.isEmpty()) {
                    planItem.setStatus(PlanItemStatus.FAULTED);
                    casePlanModel.removePlanItem(planItem.getPlanItemId());
                    LOG.warnf("TaskDefinition not found: %s", planItem.getTaskDefinitionId());
                    continue;
                }

                try {
                    tdOpt.get().execute(caseFile);
                    planItem.setStatus(PlanItemStatus.COMPLETED);
                    casePlanModel.removePlanItem(planItem.getPlanItemId());
                    newPlanItems = listenerEvaluator.evaluateAndCreatePlanItems(
                            caseFile, casePlanModel, taskDefs, planItem.getTriggerKey());
                } catch (Exception e) {
                    planItem.setStatus(PlanItemStatus.FAULTED);
                    LOG.errorf(e, "TaskDefinition %s failed", planItem.getTaskDefinitionId());
                }

                CaseStatus currentStatus = caseFile.getStatus();
                if (currentStatus == CaseStatus.COMPLETED || currentStatus == CaseStatus.FAULTED) {
                    break;
                }
            }

            for (PlanningStrategy strategy : strategies) {
                if (strategy.getActivationCondition() == ControlActivationCondition.ON_TASK_COMPLETION) {
                    strategy.reason(casePlanModel, caseFile);
                }
            }
        }

        CompletableFuture<CaseFile> future = caseFileFutures.get(id);
        if (future != null) {
            future.complete(caseFile);
        }
        // Clean up to prevent memory accumulation in long-running deployments
        activeCaseFiles.remove(id);
        casePlanModels.remove(id);
        caseFileFutures.remove(id);
    }

    public CaseFile awaitCompletion(CaseFile caseFile, Duration timeout)
            throws InterruptedException, TimeoutException {
        CompletableFuture<CaseFile> future = caseFileFutures.get(caseFile.getId());
        if (future == null) return caseFile;
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("CaseFile execution failed", e.getCause());
        }
        return caseFile;
    }

    public boolean cancel(CaseFile caseFile) {
        caseFile.setStatus(CaseStatus.CANCELLED);
        CasePlanModel casePlanModel = casePlanModels.get(caseFile.getId());
        if (casePlanModel != null) casePlanModel.clearAgenda();
        CompletableFuture<CaseFile> future = caseFileFutures.get(caseFile.getId());
        if (future != null) future.complete(caseFile);
        activeCaseFiles.remove(caseFile.getId());
        casePlanModels.remove(caseFile.getId());
        caseFileFutures.remove(caseFile.getId());
        return true;
    }

    public Collection<CaseFile> getActiveCaseFiles() {
        return activeCaseFiles.values();
    }

    public Map<String, Object> getSnapshot(CaseFile caseFile) {
        return caseFile.snapshot();
    }

    public CasePlanModel getCasePlanModel(CaseFile caseFile) {
        return casePlanModels.get(caseFile.getId());
    }

    @PreDestroy
    public void shutdown() {
        controlLoopExecutor.shutdownNow();
    }
}
