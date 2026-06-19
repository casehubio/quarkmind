package io.casehub.error;

/**
 * Thrown when the configured ConflictResolver strategy is FAIL and two TaskDefinitions attempt
 * to write to the same CaseFile key concurrently.
 */
public class ConflictException extends RuntimeException {
    private final String key;

    public ConflictException(String key) {
        super("Conflict detected for CaseFile key: " + key);
        this.key = key;
    }

    public String getKey() { return key; }
}
