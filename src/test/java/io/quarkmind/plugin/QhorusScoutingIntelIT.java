package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.SimulatedGame;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for Layer 3: ScoutingTask dispatches intel via qhorus channel,
 * TacticsTask receives it via MessageObserver and populates its intel cache.
 *
 * <p>The CaseEngine runs task execution in a background thread pool.
 * After {@code gameTick()} returns, we wait for the control loop thread
 * to execute the scouting task, commit the qhorus transaction, and for the
 * post-commit {@code MessageObserver.onMessage()} to fire.
 */
@QuarkusTest
class QhorusScoutingIntelIT {

    @Inject AgentOrchestrator orchestrator;
    @Inject SimulatedGame     simulatedGame;

    @Inject @CaseType("starcraft-game")
    DroolsTacticsTask tacticsTask;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();
        // Reset the intel cache to empty — previous tests may have populated it.
        // Uses method accessor because CDI proxy field access goes to the proxy, not the bean.
        tacticsTask.resetIntelCache();
    }

    @Test
    void afterTickWithEnemies_tacticsIntelCacheIsPopulated() throws Exception {
        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT, new Point2d(50f, 50f));

        orchestrator.gameTick();

        // CaseEngine runs tasks in a background thread pool.
        // Wait for: control loop → scouting execute → messageService.dispatch() commit
        //         → afterCompletion → DroolsTacticsTask.onMessage()
        assertWithRetry(500, 50, () ->
            assertThat(tacticsTask.currentIntelCache().threatPosition())
                .as("Threat position should be populated after scouting dispatches to qhorus")
                .isNotNull()
        );
    }

    @Test
    void afterTickWithNoEnemies_tacticsIntelCacheRemainsEmpty() throws Exception {
        orchestrator.gameTick();

        // Wait long enough for any potential dispatch to complete
        Thread.sleep(500);

        assertThat(tacticsTask.currentIntelCache().threatPosition())
            .as("Threat position should remain null when no enemies are visible")
            .isNull();
    }

    /**
     * Retries an assertion at the given interval until it passes or the total
     * timeout is exceeded. Avoids a single long sleep that slows the suite
     * while still tolerating background-thread jitter.
     */
    private static void assertWithRetry(long timeoutMs, long intervalMs,
                                         Runnable assertion) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return; // passed
            } catch (AssertionError e) {
                last = e;
            }
            Thread.sleep(intervalMs);
        }
        throw last;
    }
}
