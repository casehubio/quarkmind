package io.casehub.core;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable record of a {@link TaskDefinition}'s execution against a {@link CaseFile} -- which
 * TaskDefinition produced which keys and when. Used for provenance tracking by the
 * {@link io.casehub.core.spi.CaseFileRepository}.
 */
public class CaseFileContribution {
    private final String caseFileId;
    private final String taskDefinitionId;
    private final Set<String> keys;
    private final Instant timestamp;

    public CaseFileContribution(String caseFileId, String taskDefinitionId, Set<String> keys, Instant timestamp) {
        this.caseFileId = caseFileId;
        this.taskDefinitionId = taskDefinitionId;
        this.keys = keys;
        this.timestamp = timestamp;
    }

    public String getCaseFileId() { return caseFileId; }
    public String getTaskDefinitionId() { return taskDefinitionId; }
    public Set<String> getKeys() { return keys; }
    public Instant getTimestamp() { return timestamp; }
}
