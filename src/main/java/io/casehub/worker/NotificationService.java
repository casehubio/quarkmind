package io.casehub.worker;

import io.casehub.core.CaseFileItemEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * CDI event dispatcher for both the CaseFile and Task models. Publishes
 * {@link io.casehub.core.CaseFileItemEvent CaseFileItemEvents} and
 * {@link TaskLifecycleEvent TaskLifecycleEvents} via the Quarkus CDI Event Bus.
 */
@ApplicationScoped
public class NotificationService {

    @Inject
    Event<CaseFileItemEvent> caseFileItemEvents;

    @Inject
    Event<TaskLifecycleEvent> taskLifecycleEvents;

    public void publishCaseFileItemChange(CaseFileItemEvent event) {
        caseFileItemEvents.fire(event);
    }

    public void publishTaskLifecycle(TaskLifecycleEvent event) {
        taskLifecycleEvents.fire(event);
    }

    /**
     * Event fired when a {@link Task} transitions between {@link TaskStatus} states,
     * carrying the previous and new status for observers.
     */
    public static class TaskLifecycleEvent {
        private final String taskId;
        private final TaskStatus previousStatus;
        private final TaskStatus newStatus;

        public TaskLifecycleEvent(String taskId, TaskStatus previousStatus, TaskStatus newStatus) {
            this.taskId = taskId;
            this.previousStatus = previousStatus;
            this.newStatus = newStatus;
        }

        public String getTaskId() { return taskId; }
        public TaskStatus getPreviousStatus() { return previousStatus; }
        public TaskStatus getNewStatus() { return newStatus; }
    }
}
