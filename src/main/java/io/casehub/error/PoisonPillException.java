package io.casehub.error;

/**
 * Thrown by the TaskBroker when attempting to submit a task whose type has been quarantined by
 * the PoisonPillDetector due to repeated failures.
 */
public class PoisonPillException extends RuntimeException {
    private final String sourceId;

    public PoisonPillException(String sourceId) {
        super("Source quarantined as poison pill: " + sourceId);
        this.sourceId = sourceId;
    }

    public String getSourceId() { return sourceId; }
}
