package io.casehub.worker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Matches pending {@link Task Tasks} to available {@link Worker Workers} using a
 * {@link WorkerSelectionStrategy}. The MVP uses simple round-robin selection with
 * synchronized access.
 *
 * @see WorkerSelectionStrategy
 * @see WorkerRegistry
 */
@ApplicationScoped
public class TaskScheduler {

    @Inject
    WorkerRegistry executorRegistry;

    private final WorkerSelectionStrategy selectionStrategy = new RoundRobinStrategy();

    public Optional<Worker> selectWorker(Task task) {
        List<Worker> available = executorRegistry.getActiveWorkers();
        return selectionStrategy.selectWorker(task, available);
    }

    private static class RoundRobinStrategy implements WorkerSelectionStrategy {
        private int index = 0;

        @Override
        public synchronized Optional<Worker> selectWorker(Task task, List<Worker> available) {
            if (available.isEmpty()) {
                return Optional.empty();
            }
            Worker selected = available.get(index % available.size());
            index++;
            return Optional.of(selected);
        }
    }
}
