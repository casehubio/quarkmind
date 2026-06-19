package io.casehub.core;

import io.casehub.resilience.RetryPolicy;

import java.time.Duration;
import java.util.Set;

/**
 * An independent domain specialist in the CaseHub architecture. A TaskDefinition declares
 * entry criteria (required keys) and produced keys, and executes against the {@link CaseFile}
 * when activated by the {@link io.casehub.coordination.CaseEngine}. Supports configurable
 * execution timeout and retry policy.
 */
public interface TaskDefinition {
    String getId();
    String getName();

    Set<String> entryCriteria();
    Set<String> producedKeys();

    default boolean canActivate(CaseFile caseFile) {
        return true;
    }

    void execute(CaseFile caseFile);

    default Duration getExecutionTimeout() {
        return Duration.ofMinutes(5);
    }

    default RetryPolicy getRetryPolicy() {
        return RetryPolicy.defaults();
    }
}
