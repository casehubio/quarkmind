package io.casehub.worker;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SPI for creating and retrieving Tasks.
 *
 * Replaces TaskRegistry and TaskStorageProvider.
 * Implementations:
 * - casehub-persistence-memory: InMemoryTaskRepository
 * - casehub-persistence-hibernate: HibernateTaskRepository
 */
public interface TaskRepository {

    Task create(String taskType, Map<String, Object> context,
                Set<String> requiredCapabilities, PropagationContext propagationContext,
                CaseFile owningCase);

    Task createAutonomous(String taskType, Map<String, Object> context,
                          String assignedWorkerId, CaseFile owningCase,
                          PropagationContext propagationContext);

    Optional<Task> findById(Long id);

    List<Task> findByStatus(TaskStatus status);

    List<Task> findByWorker(String workerId);

    /** Persist state changes (no-op for in-memory). */
    void save(Task task);

    void delete(Long id);
}
