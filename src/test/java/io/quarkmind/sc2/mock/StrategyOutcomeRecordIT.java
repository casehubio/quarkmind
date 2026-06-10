package io.quarkmind.sc2.mock;

import io.casehub.ledger.memory.InMemoryActorTrustScoreRepository;
import io.casehub.ledger.memory.InMemoryLedgerEntryRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.StrategySelector;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.GameStopped;
import io.casehub.platform.api.identity.TenancyConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.event.Event;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: GameOutcomeRecorder writes the outcome record synchronously,
 * and the full trust pipeline (OutcomeRecordSaveService → IncrementalTrustUpdateObserver
 * → ActorTrustScoreRepository.upsert) materializes decisionCount after GameStopped.
 */
@QuarkusTest
class StrategyOutcomeRecordIT {

    @Inject StrategySelector strategySelector;
    @Inject TrustGateService trustGateService;
    @Inject InMemoryLedgerEntryRepository ledgerRepo;
    @Inject InMemoryActorTrustScoreRepository trustScoreRepo;
    @Inject GameSession gameSession;
    @Inject Event<GameStarted> gameStartedEvent;
    @Inject Event<GameStopped> gameStoppedEvent;

    @BeforeEach
    void setUp() {
        ledgerRepo.clear();
        trustScoreRepo.clear(); // resets accumulated decisionCount between test methods
        strategySelector.reset();
        gameSession.reset();
    }

    @Test
    void gameStopped_writesOutcomeRecord_andMaterializesDecisionCount() {
        // Given: a game was started with drools as default
        gameStartedEvent.fire(new GameStarted());
        String selectedId = strategySelector.getSelectedId();
        String context    = strategySelector.getOpponentContext();

        assertThat(selectedId).isEqualTo("strategy.drools");
        assertThat(context).isEqualTo(QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);

        // When: game stops (sync observer — records before reset can fire)
        gameStoppedEvent.fire(new GameStopped());

        // Then: ledger has an entry for this game session
        assertThat(ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID))
            .as("ledger should have at least one entry after game stop")
            .isNotEmpty();

        // And: trust score pipeline materialized decisionCount=1
        int count = trustGateService.decisionCount(selectedId, context);
        assertThat(count)
            .as("decisionCount should be 1 after one recorded game outcome")
            .isEqualTo(1);
    }

    @Test
    void gameStopped_recordsCorrectStrategyAndContext() {
        gameStartedEvent.fire(new GameStarted());
        String selectedId = strategySelector.getSelectedId();
        String context    = strategySelector.getOpponentContext();

        gameStoppedEvent.fire(new GameStopped());

        // decisionCount accumulates for the correct (strategy, context) key
        assertThat(trustGateService.decisionCount(selectedId, context)).isEqualTo(1);
        // Other (strategy, context) pairs are not affected
        assertThat(trustGateService.decisionCount("strategy.early-pressure",
            QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE)).isZero();
    }

    @Test
    void gameStopped_acrossMultipleGames_accumulatesDecisionCount() {
        // Play two games with the same strategy/context
        gameStartedEvent.fire(new GameStarted());
        gameStoppedEvent.fire(new GameStopped());

        gameSession.reset();
        gameStartedEvent.fire(new GameStarted());
        gameStoppedEvent.fire(new GameStopped());

        // decisionCount should be 2 — verifies cross-game accumulation (no LedgerLifecycleAdapter clear)
        assertThat(trustGateService.decisionCount(
            "strategy.drools", QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN)).isEqualTo(2);
    }
}
