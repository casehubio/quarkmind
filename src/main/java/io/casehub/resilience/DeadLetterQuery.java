package io.casehub.resilience;

import java.time.Instant;
import java.util.Optional;

/**
 * Query object for filtering {@link DeadLetterEntry} instances by type (PlanItem/TASK),
 * status (PENDING_REVIEW/REPLAYED/DISCARDED), time range, and result limit.
 * Used by monitoring dashboards and manual review workflows to inspect the
 * {@link DeadLetterQueue}.
 */
public class DeadLetterQuery {
    private Optional<DeadLetterEntry.DeadLetterType> type;
    private Optional<DeadLetterEntry.DeadLetterStatus> status;
    private Optional<Instant> arrivedAfter;
    private Optional<Instant> arrivedBefore;
    private int maxResults;

    public DeadLetterQuery() {
        this.type = Optional.empty();
        this.status = Optional.empty();
        this.arrivedAfter = Optional.empty();
        this.arrivedBefore = Optional.empty();
        this.maxResults = 100;
    }

    public static DeadLetterQuery all() {
        return new DeadLetterQuery();
    }

    public DeadLetterQuery withType(DeadLetterEntry.DeadLetterType type) {
        this.type = Optional.of(type);
        return this;
    }

    public DeadLetterQuery withStatus(DeadLetterEntry.DeadLetterStatus status) {
        this.status = Optional.of(status);
        return this;
    }

    public DeadLetterQuery withMaxResults(int maxResults) {
        this.maxResults = maxResults;
        return this;
    }

    public Optional<DeadLetterEntry.DeadLetterType> getType() { return type; }
    public Optional<DeadLetterEntry.DeadLetterStatus> getStatus() { return status; }
    public Optional<Instant> getArrivedAfter() { return arrivedAfter; }
    public Optional<Instant> getArrivedBefore() { return arrivedBefore; }
    public int getMaxResults() { return maxResults; }
}
