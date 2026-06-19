package io.casehub.worker;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of {@link Task}. Used directly by casehub-core and
 * casehub-persistence-memory. Hibernate persistence uses a separate entity class.
 */
public class DefaultTask implements Task {

    private static final AtomicLong ID_SEQ = new AtomicLong(0);

    private final Long id;
    private final Long version = 0L;
    private final UUID otelSpanId = UUID.randomUUID();
    private String taskType;
    private Map<String, Object> context;
    private Set<String> requiredCapabilities;
    private TaskStatus status;
    private Instant submittedAt;
    private Optional<String> assignedWorkerId;
    private Optional<Instant> assignedAt;
    private PropagationContext propagationContext;
    private TaskOrigin taskOrigin;
    private CaseFile owningCase;
    private final List<Task> childTasks = new CopyOnWriteArrayList<>();

    public DefaultTask() {
        this.id = ID_SEQ.incrementAndGet();
        this.context = new HashMap<>();
        this.requiredCapabilities = new HashSet<>();
        this.status = TaskStatus.PENDING;
        this.submittedAt = Instant.now();
        this.assignedWorkerId = Optional.empty();
        this.assignedAt = Optional.empty();
        this.taskOrigin = TaskOrigin.BROKER_ALLOCATED;
    }

    public DefaultTask(TaskRequest request) {
        this();
        this.taskType = request.getTaskType();
        this.context = new HashMap<>(request.getContext());
        this.requiredCapabilities = new HashSet<>(request.getRequiredCapabilities());
        this.propagationContext = request.getPropagationContext();
    }

    @Override public Long getId()                              { return id; }
    @Override public Long getVersion()                        { return version; }
    @Override public UUID getOtelSpanId()                     { return otelSpanId; }
    @Override public String getTaskType()                     { return taskType; }
    @Override public void setTaskType(String t)               { this.taskType = t; }
    @Override public Map<String, Object> getContext()         { return context; }
    @Override public void setContext(Map<String, Object> c)   { this.context = c; }
    @Override public Set<String> getRequiredCapabilities()    { return requiredCapabilities; }
    @Override public void setRequiredCapabilities(Set<String> c) { this.requiredCapabilities = c; }
    @Override public TaskStatus getStatus()                   { return status; }
    @Override public void setStatus(TaskStatus s)             { this.status = s; }
    @Override public Instant getSubmittedAt()                 { return submittedAt; }
    @Override public Optional<String> getAssignedWorkerId()   { return assignedWorkerId; }
    @Override public void setAssignedWorkerId(String w) {
        this.assignedWorkerId = Optional.ofNullable(w);
        this.assignedAt = Optional.of(Instant.now());
    }
    @Override public Optional<Instant> getAssignedAt()           { return assignedAt; }
    @Override public PropagationContext getPropagationContext()   { return propagationContext; }
    @Override public void setPropagationContext(PropagationContext c) { this.propagationContext = c; }
    @Override public TaskOrigin getTaskOrigin()                   { return taskOrigin; }
    @Override public void setTaskOrigin(TaskOrigin o)             { this.taskOrigin = o; }
    @Override public Optional<CaseFile> getOwningCase()           { return Optional.ofNullable(owningCase); }
    @Override public void setOwningCase(CaseFile c)               { this.owningCase = c; }
    @Override public List<Task> getChildTasks()                   { return Collections.unmodifiableList(childTasks); }
    @Override public void addChildTask(Task t)                    { this.childTasks.add(t); }
}
