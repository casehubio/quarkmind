package io.quarkmind.qa.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@UnlessBuildProfile("prod")
@ApplicationScoped
public class WorkbenchBroadcaster {

    private static final Logger log = Logger.getLogger(WorkbenchBroadcaster.class);

    @Inject
    ObjectMapper objectMapper;

    private final Set<WebSocketConnection> sessions = new CopyOnWriteArraySet<>();

    private volatile WorkbenchEvent latestPattern;
    private volatile WorkbenchEvent latestStrategy;
    private volatile WorkbenchEvent latestCoaching;

    WorkbenchBroadcaster() {}

    void addSession(WebSocketConnection connection) {
        sessions.add(connection);
        pushSnapshot(connection);
        log.infof("[WORKBENCH] Client connected — %d active", sessions.size());
    }

    void removeSession(WebSocketConnection connection) {
        sessions.remove(connection);
        log.infof("[WORKBENCH] Client disconnected — %d active", sessions.size());
    }

    public void waitForSession(long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (sessions.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    public void broadcast(WorkbenchEvent event) {
        updateSnapshot(event);
        if (sessions.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(event);
            sessions.forEach(s -> s.sendText(json)
                .subscribe().with(
                    ignored -> {},
                    err -> log.warnf("[WORKBENCH] Send failed: %s", err.getMessage())));
        } catch (Exception e) {
            log.warnf(e, "[WORKBENCH] Serialisation failed: %s", e.getMessage());
        }
    }

    private void updateSnapshot(WorkbenchEvent event) {
        switch (event.type()) {
            case "pattern"  -> latestPattern  = event;
            case "strategy" -> latestStrategy = event;
            case "coaching" -> latestCoaching = event;
            default -> {} // coaching_compliance does not replace snapshot
        }
    }

    private void pushSnapshot(WebSocketConnection connection) {
        try {
            if (latestPattern != null) sendOne(connection, latestPattern);
            if (latestStrategy != null) sendOne(connection, latestStrategy);
            if (latestCoaching != null) sendOne(connection, latestCoaching);
        } catch (Exception e) {
            log.warnf(e, "[WORKBENCH] Snapshot push failed: %s", e.getMessage());
        }
    }

    private void sendOne(WebSocketConnection connection, WorkbenchEvent event) throws Exception {
        String json = objectMapper.writeValueAsString(event);
        connection.sendText(json).subscribe().with(ignored -> {}, err -> {});
    }
}
