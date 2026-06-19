package io.casehub.persistence.memory;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.worker.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link TaskRepository}. Thread-safe, no external dependencies.
 */
@ApplicationScoped
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<Long, Task> store = new ConcurrentHashMap<>();

    @Override
    public Task create(String taskType, Map<String, Object> context,
                       Set<String> requiredCapabilities, PropagationContext propagationContext,
                       CaseFile owningCase) {
        InMemoryTask task = new InMemoryTask();
        task.setTaskType(taskType);
        task.setContext(new HashMap<>(context));
        task.setRequiredCapabilities(new HashSet<>(requiredCapabilities));
        task.setPropagationContext(propagationContext);
        task.setOwningCase(owningCase);
        task.setTaskOrigin(TaskOrigin.BROKER_ALLOCATED);
        store.put(task.getId(), task);
        if (owningCase instanceof InMemoryCaseFile icf) icf.addTask(task);
        return task;
    }

    @Override
    public Task createAutonomous(String taskType, Map<String, Object> context,
                                  String assignedWorkerId, CaseFile owningCase,
                                  PropagationContext propagationContext) {
        InMemoryTask task = new InMemoryTask();
        task.setTaskType(taskType);
        task.setContext(new HashMap<>(context));
        task.setPropagationContext(propagationContext);
        task.setOwningCase(owningCase);
        task.setTaskOrigin(TaskOrigin.AUTONOMOUS);
        task.setAssignedWorkerId(assignedWorkerId);
        task.setStatus(TaskStatus.ASSIGNED);
        store.put(task.getId(), task);
        if (owningCase instanceof InMemoryCaseFile icf) icf.addTask(task);
        return task;
    }

    @Override public Optional<Task> findById(Long id) { return Optional.ofNullable(store.get(id)); }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        return store.values().stream()
                .filter(t -> t.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findByWorker(String workerId) {
        return store.values().stream()
                .filter(t -> t.getAssignedWorkerId().map(workerId::equals).orElse(false))
                .collect(Collectors.toList());
    }

    @Override public void save(Task task) { /* no-op */ }
    @Override public void delete(Long id) { store.remove(id); }
}
