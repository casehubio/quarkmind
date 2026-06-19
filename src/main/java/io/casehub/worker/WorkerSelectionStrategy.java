package io.casehub.worker;

import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for selecting a worker to handle a {@link Task} from a list of
 * available workers. The MVP uses round-robin; future implementations may use capability
 * matching or load balancing.
 *
 * @see TaskScheduler
 */
public interface WorkerSelectionStrategy {
    Optional<Worker> selectWorker(Task task, List<Worker> availableExecutors);
}
