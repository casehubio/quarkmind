package io.casehub.error;

/**
 * Thrown when a Task cannot be submitted via the TaskBroker, for example because the queue is
 * full, the request is invalid, or the task type has been quarantined.
 */
public class TaskSubmissionException extends RuntimeException {
    public TaskSubmissionException(String message) { super(message); }
    public TaskSubmissionException(String message, Throwable cause) { super(message, cause); }
}
