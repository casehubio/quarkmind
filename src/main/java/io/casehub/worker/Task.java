package io.casehub.worker;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A concrete, executable work unit in the request-response model.
 * Has a Long primary key and UUID for OpenTelemetry span tracking.
 * Carries a direct reference to its owning CaseFile and any child tasks,
 * forming part of the POJO object graph.
 */
public interface Task {

    // Identity
    Long getId();
    Long getVersion();
    UUID getOtelSpanId();

    // Task data
    String getTaskType();
    void setTaskType(String taskType);
    Map<String, Object> getContext();
    void setContext(Map<String, Object> context);
    Set<String> getRequiredCapabilities();
    void setRequiredCapabilities(Set<String> capabilities);

    // Lifecycle
    TaskStatus getStatus();
    void setStatus(TaskStatus status);
    Instant getSubmittedAt();
    Optional<String> getAssignedWorkerId();
    void setAssignedWorkerId(String workerId);
    Optional<Instant> getAssignedAt();
    TaskOrigin getTaskOrigin();
    void setTaskOrigin(TaskOrigin origin);

    // Context propagation
    PropagationContext getPropagationContext();
    void setPropagationContext(PropagationContext context);

    // Graph relationships
    Optional<CaseFile> getOwningCase();
    void setOwningCase(CaseFile caseFile);
    List<Task> getChildTasks();
    void addChildTask(Task task);
}
