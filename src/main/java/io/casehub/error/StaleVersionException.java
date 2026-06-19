package io.casehub.error;

/**
 * Thrown by {@code CaseFile.putIfVersion()} when the expected version does not match the current
 * version, indicating a concurrent modification. This supports optimistic concurrency control
 * on CaseFile entries.
 */
public class StaleVersionException extends RuntimeException {
    private final String key;
    private final long expectedVersion;
    private final long actualVersion;

    public StaleVersionException(String key, long expectedVersion, long actualVersion) {
        super("Stale version for key '" + key + "': expected " + expectedVersion + " but was " + actualVersion);
        this.key = key;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String getKey() { return key; }
    public long getExpectedVersion() { return expectedVersion; }
    public long getActualVersion() { return actualVersion; }
}
