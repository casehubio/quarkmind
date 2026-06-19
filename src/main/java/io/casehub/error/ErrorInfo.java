package io.casehub.error;

import java.time.Instant;
import java.util.Optional;

/**
 * Structured error information attached to failed Tasks and TaskDefinition contributions.
 * Carries an error code, message, retryability flag, timestamp, and optional worker and
 * stack trace details. Used throughout the resilience layer for retry and dead-letter handling.
 */
public class ErrorInfo {
    private String errorCode;
    private String message;
    private boolean retryable;
    private Instant timestamp;
    private Optional<String> workerId;
    private Optional<String> stackTrace;

    public ErrorInfo() {
        this.timestamp = Instant.now();
        this.workerId = Optional.empty();
        this.stackTrace = Optional.empty();
    }

    public ErrorInfo(String errorCode, String message, boolean retryable) {
        this();
        this.errorCode = errorCode;
        this.message = message;
        this.retryable = retryable;
    }

    // Getters and setters
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Optional<String> getWorkerId() { return workerId; }
    public void setWorkerId(Optional<String> workerId) { this.workerId = workerId; }
    public Optional<String> getStackTrace() { return stackTrace; }
    public void setStackTrace(Optional<String> stackTrace) { this.stackTrace = stackTrace; }
}
