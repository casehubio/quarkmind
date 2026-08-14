package io.quarkmind.sc2.mock;

import io.casehub.ledger.memory.InMemoryLedgerEntryRepository;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.agency.context.MapCaseContext;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.cbr.SC2StrategyRouterTask;
import io.quarkmind.agent.TrustTestUtils;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.IntentQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.event.Event;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: trust-weighted strategy routing selects the QUALIFIED strategy
 * when a seeded trust score exists, and blocks the non-selected strategies.
 *
 * Verifies the dual-strategy invariant: exactly one StrategyTask fires per tick.
 */
@QuarkusTest
class TrustWeightedStrategyIT {

    @Inject SC2StrategyRouterTask strategyRouter;
    @Inject TrustGateService trustGateService;
    @Inject ActorTrustScoreRepository trustScoreRepo;
    @Inject InMemoryLedgerEntryRepository ledgerRepo;
    @Inject SimulatedGame simulatedGame;
    @Inject IntentQueue intentQueue;
    @Inject AgentOrchestrator orchestrator;
    @Inject Event<GameStarted> gameStartedEvent;
    @Inject @Any Instance<StrategyTask> strategyTasks;

    @BeforeEach
    void setUp() {
        ledgerRepo.clear();
        // Router resets naturally via CaseFile on new game
        simulatedGame.reset();
        intentQueue.drainAll();
        TrustTestUtils.seedQualified(trustScoreRepo,
            "strategy.early-pressure", QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);
    }

    @Test
    void fullTick_withQualifiedScore_forVsUnknown_droolsSelectedButInactive() {
        TrustTestUtils.seedQualified(trustScoreRepo,
            "strategy.early-pressure", QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
        orchestrator.startGame();
        assertThat(strategyRouter.lastSelectedId()).isEqualTo("strategy.early-pressure");
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();
        assertThat(result.solveSucceeded()).isTrue();
    }

    @Test
    void gameStarted_withSeedForVsUnknown_selectsDroolsFallback() {
        gameStartedEvent.fire(new GameStarted());
        assertThat(strategyRouter.lastSelectedId()).isEqualTo("strategy.drools");
    }

    @Test
    void gameStarted_afterSeedingEarlyPressureForVsAggressive_andManualContextOverride() {
        gameStartedEvent.fire(new GameStarted());
        assertThat(trustGateService.decisionCount("strategy.early-pressure",
            QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE)).isEqualTo(12);
        assertThat(trustGateService.currentScore("strategy.early-pressure",
            QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE).getAsDouble()).isGreaterThan(0.73);
    }

    @Test
    void testActivation_onlySelectedStrategy_returnsTrue() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE,
            QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.early-pressure"));

        strategyTasks.forEach(task -> {
            if ("strategy.early-pressure".equals(task.getId())) {
                assertThat(task.testActivation(ctx))
                    .as("early-pressure testActivation should be true").isTrue();
            } else {
                assertThat(task.testActivation(ctx))
                    .as(task.getId() + " testActivation should be false (not selected)").isFalse();
            }
        });
    }
}
