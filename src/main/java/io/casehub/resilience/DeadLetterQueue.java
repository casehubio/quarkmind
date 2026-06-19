package io.casehub.resilience;

import io.casehub.control.PlanItem;
import io.casehub.core.CaseFile;
import io.casehub.error.ErrorInfo;
import io.casehub.worker.Task;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Stores failed PlanItem executions and Tasks that have exhausted all retries.
 * Supports listing, replay (re-create with original context), discard (acknowledge as
 * unrecoverable), and TTL-based purge. The current MVP implementation is in-memory;
 * Phase 2 targets Redis with at-least-once replay guarantees.
 *
 * @see DeadLetterEntry
 * @see DeadLetterQuery
 */
@ApplicationScoped
public class DeadLetterQueue {

    private final Map<String, DeadLetterEntry> entries = new ConcurrentHashMap<>();

    public DeadLetterEntry sendToDeadLetter(PlanItem planItem, CaseFile caseFile,
                                             List<RetryState.RetryAttempt> retryHistory,
                                             ErrorInfo finalError) {
        DeadLetterEntry entry = new DeadLetterEntry();
        entry.setType(DeadLetterEntry.DeadLetterType.PlanItem);
        entry.setOriginalId(planItem.getPlanItemId());
        entry.setCaseFileId(caseFile.getId().toString());
        entry.setOriginalContext(caseFile.snapshot());
        entry.setPropagationContext(caseFile.getPropagationContext());
        entry.setFinalError(finalError);
        entry.setRetryHistory(retryHistory);
        entry.setTotalAttempts(retryHistory.size());
        entries.put(entry.getDeadLetterId(), entry);
        return entry;
    }

    public DeadLetterEntry sendToDeadLetter(Task task,
                                             List<RetryState.RetryAttempt> retryHistory,
                                             ErrorInfo finalError) {
        DeadLetterEntry entry = new DeadLetterEntry();
        entry.setType(DeadLetterEntry.DeadLetterType.TASK);
        entry.setOriginalId(task.getId().toString());
        entry.setTaskType(task.getTaskType());
        entry.setOriginalContext(task.getContext());
        entry.setPropagationContext(task.getPropagationContext());
        entry.setFinalError(finalError);
        entry.setRetryHistory(retryHistory);
        entry.setTotalAttempts(retryHistory.size());
        entries.put(entry.getDeadLetterId(), entry);
        return entry;
    }

    public List<DeadLetterEntry> list(DeadLetterQuery query) {
        return entries.values().stream()
                .filter(e -> query.getType().map(t -> t == e.getType()).orElse(true))
                .filter(e -> query.getStatus().map(s -> s == e.getStatus()).orElse(true))
                .limit(query.getMaxResults())
                .collect(Collectors.toList());
    }

    public void replay(String deadLetterId) {
        DeadLetterEntry entry = entries.get(deadLetterId);
        if (entry != null) {
            entry.setStatus(DeadLetterEntry.DeadLetterStatus.REPLAYED);
            // TODO: re-create PlanItem or Task with original context
        }
    }

    public void discard(String deadLetterId) {
        DeadLetterEntry entry = entries.get(deadLetterId);
        if (entry != null) {
            entry.setStatus(DeadLetterEntry.DeadLetterStatus.DISCARDED);
        }
    }

    public void purge(Duration olderThan) {
        Instant cutoff = Instant.now().minus(olderThan);
        entries.entrySet().removeIf(e -> e.getValue().getArrivedAt().isBefore(cutoff));
    }
}
