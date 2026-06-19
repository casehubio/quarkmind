package io.casehub.persistence.memory;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.worker.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of {@link Task}.
 */
class InMemoryTask implements Task {

    private static final AtomicLong ID_SEQ = new AtomicLong(0);

    private final Long id = ID_SEQ.incrementAndGet();
    private final Long version = 0L;
    private final UUID otelSpanId = UUID.randomUUID();

    private String taskType;
    private Map<String, Object> context = new HashMap<>();
    private Set<String> requiredCapabilities = new HashSet<>();
    private TaskStatus status = TaskStatus.PENDING;
    private final Instant submittedAt = Instant.now();
    private Optional<String> assignedWorkerId = Optional.empty();
    private Optional<Instant> assignedAt = Optional.empty();
    private PropagationContext propagationContext;
    private TaskOrigin taskOrigin = TaskOrigin.BROKER_ALLOCATED;
    private CaseFile owningCase;
    private final List<Task> childTasks = new CopyOnWriteArrayList<>();

    InMemoryTask() {}

    @Override public Long getId()                                  { return id; }
    /** Always returns 0 — in-memory tasks are not versioned. Hibernate @Version manages this for persistent tasks. */
    @Override public Long getVersion()                             { return version; }
    @Override public UUID getOtelSpanId()                          { return otelSpanId; }
    @Override public String getTaskType()                          { return taskType; }
    @Override public void setTaskType(String t)                    { this.taskType = t; }
    @Override public Map<String, Object> getContext()              { return context; }
    @Override public void setContext(Map<String, Object> c)        { this.context = c; }
    @Override public Set<String> getRequiredCapabilities()         { return requiredCapabilities; }
    @Override public void setRequiredCapabilities(Set<String> c)   { this.requiredCapabilities = c; }
    @Override public TaskStatus getStatus()                        { return status; }
    @Override public void setStatus(TaskStatus s)                  { this.status = s; }
    @Override public Instant getSubmittedAt()                      { return submittedAt; }
    @Override public Optional<String> getAssignedWorkerId()        { return assignedWorkerId; }
    @Override public void setAssignedWorkerId(String w) {
        this.assignedWorkerId = Optional.ofNullable(w);
        this.assignedAt = Optional.of(Instant.now());
    }
    @Override public Optional<Instant> getAssignedAt()            { return assignedAt; }
    @Override public PropagationContext getPropagationContext()    { return propagationContext; }
    @Override public void setPropagationContext(PropagationContext c) { this.propagationContext = c; }
    @Override public TaskOrigin getTaskOrigin()                    { return taskOrigin; }
    @Override public void setTaskOrigin(TaskOrigin o)              { this.taskOrigin = o; }
    @Override public Optional<CaseFile> getOwningCase()            { return Optional.ofNullable(owningCase); }
    @Override public void setOwningCase(CaseFile c)                { this.owningCase = c; }
    @Override public List<Task> getChildTasks()                    { return Collections.unmodifiableList(childTasks); }
    @Override public void addChildTask(Task t)                     { this.childTasks.add(t); }
}
