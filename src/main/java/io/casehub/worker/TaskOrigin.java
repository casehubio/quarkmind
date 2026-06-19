package io.casehub.worker;

/**
 * Distinguishes how a Task was initiated.
 *
 * <ul>
 *   <li>{@link #BROKER_ALLOCATED} - Traditional request-response: TaskBroker receives a
 *       TaskRequest, TaskScheduler selects and assigns a Worker.</li>
 *   <li>{@link #AUTONOMOUS} - Decentralized/self-initiated: Worker observes context on its
 *       own agency, decides to work, and notifies the system. The system tracks this work
 *       as part of an overall case with full PropagationContext lineage support.</li>
 * </ul>
 */
public enum TaskOrigin {
    /**
     * Task created by TaskBroker in response to a TaskRequest.
     * TaskScheduler allocates a Worker based on capability matching.
     */
    BROKER_ALLOCATED,

    /**
     * Task self-initiated by an autonomous Worker.
     * Worker observes context, decides to work, and registers the task with the system
     * for tracking and lineage. No allocation step - Worker immediately claims ownership.
     */
    AUTONOMOUS
}
