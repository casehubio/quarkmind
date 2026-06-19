package io.casehub.error;

/**
 * Thrown when a CaseFile cannot be created by the CaseEngine, for example due to an invalid
 * case type, a storage failure, or missing TaskDefinitions.
 */
public class CaseCreationException extends RuntimeException {
    public CaseCreationException(String message) { super(message); }
    public CaseCreationException(String message, Throwable cause) { super(message, cause); }
}
