package io.casehub.worker;

import java.time.Instant;
import java.util.Set;

/**
 * Represents a registered worker with its capabilities, heartbeat tracking, and active status.
 * Workers register with the {@link WorkerRegistry}, claim tasks matching their capabilities,
 * and submit results.
 *
 * @see WorkerRegistry
 */
public class Worker {
    private String workerId;
    private String workerName;
    private Set<String> capabilities;
    private Instant registeredAt;
    private Instant lastHeartbeat;
    private boolean active;

    public Worker(String workerId, String workerName, Set<String> capabilities) {
        this.workerId = workerId;
        this.workerName = workerName;
        this.capabilities = capabilities;
        this.registeredAt = Instant.now();
        this.lastHeartbeat = Instant.now();
        this.active = true;
    }

    public String getWorkerId() { return workerId; }
    public String getWorkerName() { return workerName; }
    public Set<String> getCapabilities() { return capabilities; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
