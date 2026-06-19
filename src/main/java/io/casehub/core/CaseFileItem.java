package io.casehub.core;

import java.time.Instant;

/**
 * Wraps a CaseFile key's value with version metadata for optimistic concurrency control. Tracks the
 * version number, which TaskDefinition wrote it, when it was written, and which CaseHub
 * instance originated the write (for distributed mode). Used by the ConflictResolver and
 * {@link CaseFile#putIfVersion(String, Object, long)}.
 */
public class CaseFileItem {
    private Object value;
    private long version;
    private String writtenBy;
    private Instant writtenAt;
    private String instanceId;

    public CaseFileItem() {}

    public CaseFileItem(Object value, long version, String writtenBy) {
        this.value = value;
        this.version = version;
        this.writtenBy = writtenBy;
        this.writtenAt = Instant.now();
    }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public String getWrittenBy() { return writtenBy; }
    public void setWrittenBy(String writtenBy) { this.writtenBy = writtenBy; }
    public Instant getWrittenAt() { return writtenAt; }
    public void setWrittenAt(Instant writtenAt) { this.writtenAt = writtenAt; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
}
