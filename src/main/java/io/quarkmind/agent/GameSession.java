package io.quarkmind.agent;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * Holds the current game session UUID for ledger subjectId scoping.
 * Reset in AgentOrchestrator.startGame() only — NOT in stopGame() (async
 * CDI observers dispatched during final ticks must attribute to the correct session).
 */
@ApplicationScoped
public class GameSession {
    private volatile UUID id = UUID.randomUUID();

    public UUID id() { return id; }

    // public — required for CDI proxy access in @QuarkusTest; package-private is unreachable
    // through @ApplicationScoped proxies even from the same package.
    public void reset() { id = UUID.randomUUID(); }
}
