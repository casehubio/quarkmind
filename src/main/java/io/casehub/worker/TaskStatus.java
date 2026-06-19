package io.casehub.worker;

/**
 * Lifecycle states for a Task in the request-response model, aligned with
 * CNCF Serverless Workflow (OWL) lifecycle phases. ASSIGNED is a task-specific
 * extension (a Worker has claimed the task but not yet started execution).
 *
 * @see Task
 */
public enum TaskStatus {
    PENDING,
    ASSIGNED,
    RUNNING,
    WAITING,
    SUSPENDED,
    COMPLETED,
    FAULTED,
    CANCELLED
}
