package io.casehub.core;

import java.time.Instant;
import java.util.Optional;

/**
 * Event published when a key is added or updated on a {@link CaseFile}. Carries the CaseFile ID, key,
 * new value, previous value, and the contributing TaskDefinition ID. Consumed by the
 * {@link ListenerEvaluator} to trigger precondition re-evaluation and by the NotificationService
 * for CDI event dispatch.
 */
public class CaseFileItemEvent {
    private final String caseFileId;
    private final String key;
    private final Object value;
    private final Optional<Object> previousValue;
    private final Optional<String> contributorId;
    private final Instant timestamp;

    public CaseFileItemEvent(String caseFileId, String key, Object value,
                            Optional<Object> previousValue, Optional<String> contributorId) {
        this.caseFileId = caseFileId;
        this.key = key;
        this.value = value;
        this.previousValue = previousValue;
        this.contributorId = contributorId;
        this.timestamp = Instant.now();
    }

    public String getCaseFileId() { return caseFileId; }
    public String getKey() { return key; }
    public Object getValue() { return value; }
    public Optional<Object> getPreviousValue() { return previousValue; }
    public Optional<String> getContributorId() { return contributorId; }
    public Instant getTimestamp() { return timestamp; }
}
