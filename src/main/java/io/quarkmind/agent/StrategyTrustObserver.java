package io.quarkmind.agent;

import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Selects the active {@link StrategyTask} at game start and at the mid-game checkpoint (L6).
 *
 * <p>Both observers use {@code @Observes} (synchronous):
 * <ul>
 *   <li>{@link #onGameStarted} — fires inline inside {@code AgentOrchestrator.startGame()},
 *       before any {@code gameTick()} can be scheduled. No race with the first tick.</li>
 *   <li>{@link #onPostureClassified} — fires inside {@code DroolsScoutingTask.execute()},
 *       mid-tick. The strategy pivot takes effect in the same tick's CaseEngine
 *       re-evaluation pass (see spec §Same-tick pivot).</li>
 * </ul>
 *
 * <p>Candidate IDs are derived at event time from the CDI-discovered
 * {@code @Any Instance<StrategyTask>} — adding a new strategy bean automatically includes it.
 */
@ApplicationScoped
public class StrategyTrustObserver {

    private static final Logger log = Logger.getLogger(StrategyTrustObserver.class);

    @Inject StrategySelector    strategySelector;
    @Inject StrategyTrustRouter trustRouter;

    @Inject @Any Instance<StrategyTask> strategyTasks;

    void onGameStarted(@Observes GameStarted event) {
        strategySelector.reset();
        List<String> candidateIds = candidateIds();
        String winner = trustRouter.select(candidateIds, QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
        strategySelector.selectForGame(winner, QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
        log.infof("[TRUST] Game start — selected %s (bootstrap context, candidates=%s)",
            winner, candidateIds);
    }

    void onPostureClassified(@Observes EnemyPostureClassifiedEvent event) {
        if (!strategySelector.claimCheckpoint()) return; // one pivot per game
        String context = mapPostureToContext(event.posture());
        List<String> candidateIds = candidateIds();
        String winner = trustRouter.select(candidateIds, context);
        strategySelector.selectForGame(winner, context);
        log.infof("[TRUST] Checkpoint — opponent=%s → context=%s → selected %s (effective this tick)",
            event.posture(), context, winner);
    }

    private List<String> candidateIds() {
        return strategyTasks.stream()
            .map(StrategyTask::getId)
            .toList();
    }

    static String mapPostureToContext(String posture) {
        return switch (posture) {
            case "AGGRESSIVE" -> QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE;
            case "ECONOMIC"   -> QuarkMindCapabilityTag.STRATEGY_VS_ECONOMIC;
            case "DEFENSIVE"  -> QuarkMindCapabilityTag.STRATEGY_VS_DEFENSIVE;
            default           -> QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN;
        };
    }
}
