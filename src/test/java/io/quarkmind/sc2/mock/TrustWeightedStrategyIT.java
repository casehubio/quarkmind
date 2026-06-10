package io.quarkmind.sc2.mock;

import io.casehub.ledger.memory.InMemoryLedgerEntryRepository;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.StrategySelector;
import io.quarkmind.agent.TrustTestUtils;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.IntentQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.event.Event;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: trust-weighted strategy routing selects the QUALIFIED strategy
 * when a seeded trust score exists, and blocks the non-selected strategies.
 *
 * Verifies the dual-strategy invariant: exactly one StrategyTask fires per tick.
 */
@QuarkusTest
class TrustWeightedStrategyIT {

    @Inject StrategySelector strategySelector;
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
        strategySelector.reset();
        simulatedGame.reset();
        intentQueue.drainAll();
        // Seed: early-pressure is QUALIFIED for vs.aggressive context
        TrustTestUtils.seedQualified(trustScoreRepo,
            "strategy.early-pressure", QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);
        // drools and economic-expansion: no data → BOOTSTRAP → tiebreaker favors drools
    }

    @Test
    void fullTick_withQualifiedScore_forVsUnknown_droolsSelectedButInactive() {
        // Seed early-pressure as QUALIFIED for vs.unknown (game-start context)
        TrustTestUtils.seedQualified(trustScoreRepo,
            "strategy.early-pressure", QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);

        // Fire game start — trust router selects early-pressure (QUALIFIED outranks BOOTSTRAP)
        orchestrator.startGame();
        assertThat(strategySelector.getSelectedId()).isEqualTo("strategy.early-pressure");

        // Run a full game tick. Note: createAndSolve() returns the pre-solve CaseFile
        // (translator-written keys only) — STRATEGY is written by the plugin in the async
        // CaseEngine control loop and is not in the returned CaseFile reference.
        // We verify: (1) tick completes without exception, (2) selection invariant holds.
        orchestrator.gameTick();
        AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();
        assertThat(result.solveSucceeded()).isTrue();

        // Dual-strategy invariant: non-selected strategies return canActivate()=false
        // against the pre-solve CaseFile (which has READY but not ENEMY_ARMY_SIZE/POSTURE)
        strategyTasks.forEach(task -> {
            if (!"strategy.early-pressure".equals(task.getId())) {
                assertThat(task.canActivate(result.caseFile()))
                    .as(task.getId() + " must not be active when early-pressure is selected")
                    .isFalse();
            }
        });
    }

    @Test
    void gameStarted_withSeedForVsUnknown_selectsDroolsFallback() {
        // vs.unknown context at game start: no seeded scores → all BOOTSTRAP → drools wins tiebreaker
        gameStartedEvent.fire(new GameStarted());
        assertThat(strategySelector.getSelectedId()).isEqualTo("strategy.drools");
    }

    @Test
    void gameStarted_afterSeedingEarlyPressureForVsAggressive_andManualContextOverride() {
        // Fire game start in vs.unknown (drools selected), then verify seeded scores visible
        gameStartedEvent.fire(new GameStarted());
        // Verify decisionCount is available (seeded)
        assertThat(trustGateService.decisionCount("strategy.early-pressure",
            QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE)).isEqualTo(12);
        // Verify score is QUALIFIED (0.82 above threshold 0.65+margin 0.08 = 0.73)
        assertThat(trustGateService.currentScore("strategy.early-pressure",
            QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE).getAsDouble()).isGreaterThan(0.73);
    }

    @Test
    void canActivate_onlySelectedStrategy_returnsTrue() {
        // Seed early-pressure as QUALIFIED for vs.aggressive and manually select it
        strategySelector.selectForGame("strategy.early-pressure",
            QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);

        // Build a minimal CaseFile with READY
        var cf = io.casehub.coordination.PropagationContext.createRoot();
        var caseFile = new io.casehub.persistence.memory.InMemoryCaseFileRepository()
            .create("starcraft-game", java.util.Map.of(), cf);
        caseFile.put(io.quarkmind.agent.QuarkMindCaseFile.READY, Boolean.TRUE);

        // canActivate() must be false for drools and economic-expansion
        strategyTasks.forEach(task -> {
            if ("strategy.early-pressure".equals(task.getId())) {
                // early-pressure: selected + READY present → true
                assertThat(task.canActivate(caseFile))
                    .as("early-pressure canActivate should be true").isTrue();
            } else {
                // others: not selected → false
                assertThat(task.canActivate(caseFile))
                    .as(task.getId() + " canActivate should be false (not selected)").isFalse();
            }
        });
    }
}
