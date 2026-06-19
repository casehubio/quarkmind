package io.casehub.core;

/**
 * Lifecycle states for a {@link CaseFile}, aligned with CNCF Serverless Workflow (OWL)
 * lifecycle phases. Transitions govern when KnowledgeSources may contribute and
 * when the case file is considered terminal.
 */
public enum CaseStatus {
    PENDING,
    RUNNING,
    WAITING,
    SUSPENDED,
    COMPLETED,
    FAULTED,
    CANCELLED
}
