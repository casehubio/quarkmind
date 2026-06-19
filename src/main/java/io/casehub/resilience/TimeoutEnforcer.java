package io.casehub.resilience;

import io.casehub.coordination.CaseEngine;
import io.casehub.core.CaseFile;
import io.casehub.core.CaseStatus;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Quarkus Scheduler component that actively monitors all in-flight CaseFiles, executing
 * PlanItems, and Tasks against their configured deadlines. Transitions work items to
 * FAULTED when deadlines expire, with cascading cancellation propagated to all
 * descendants in the {@code PropagationContext} tree.
 */
@ApplicationScoped
public class TimeoutEnforcer {

    @Inject
    CaseEngine caseEngine;

    @Scheduled(every = "${casehub.timeout.check-interval:1s}")
    public void enforceTimeouts() {
        for (CaseFile caseFile : caseEngine.getActiveCaseFiles()) {
            if (caseFile.getStatus() == CaseStatus.RUNNING
                    && caseFile.getPropagationContext().isBudgetExhausted()) {
                caseEngine.cancel(caseFile);
                caseFile.setStatus(CaseStatus.FAULTED);
            }
        }
    }
}
