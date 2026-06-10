package io.quarkmind.sc2.mock;

import io.casehub.ledger.memory.InMemoryLedgerEntryRepository;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.EnemyPostureClassifiedEvent;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.StrategySelector;
import io.quarkmind.agent.TrustTestUtils;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.event.Event;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: mid-game checkpoint pivots strategy exactly once per game.
 */
@QuarkusTest
class StrategyCheckpointIT {

    @Inject StrategySelector strategySelector;
    @Inject ActorTrustScoreRepository trustScoreRepo;
    @Inject InMemoryLedgerEntryRepository ledgerRepo;
    @Inject Event<GameStarted> gameStartedEvent;
    @Inject Event<EnemyPostureClassifiedEvent> postureClassifiedEvent;

    @BeforeEach
    void setUp() {
        ledgerRepo.clear();
        strategySelector.reset();
        TrustTestUtils.seedQualified(trustScoreRepo,
            "strategy.early-pressure", QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);
    }

    @Test
    void checkpoint_pivotsFromDroolsToEarlyPressure_onAggressivePosture() {
        gameStartedEvent.fire(new GameStarted());
        assertThat(strategySelector.getSelectedId()).isEqualTo("strategy.drools");
        assertThat(strategySelector.isCheckpointFired()).isFalse();

        postureClassifiedEvent.fire(new EnemyPostureClassifiedEvent("AGGRESSIVE"));

        assertThat(strategySelector.getSelectedId()).isEqualTo("strategy.early-pressure");
        assertThat(strategySelector.getOpponentContext())
            .isEqualTo(QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE);
        assertThat(strategySelector.isCheckpointFired()).isTrue();
    }

    @Test
    void checkpoint_firesOnlyOnce_subsequentEventsIgnored() {
        gameStartedEvent.fire(new GameStarted());
        postureClassifiedEvent.fire(new EnemyPostureClassifiedEvent("AGGRESSIVE"));
        String afterFirst = strategySelector.getSelectedId();

        // Second event should be ignored
        postureClassifiedEvent.fire(new EnemyPostureClassifiedEvent("DEFENSIVE"));

        assertThat(strategySelector.getSelectedId())
            .as("second posture event must not change selection").isEqualTo(afterFirst);
    }

    @Test
    void checkpoint_reset_allowsNewPivotNextGame() {
        gameStartedEvent.fire(new GameStarted());
        postureClassifiedEvent.fire(new EnemyPostureClassifiedEvent("AGGRESSIVE"));
        assertThat(strategySelector.isCheckpointFired()).isTrue();

        // New game start resets everything
        gameStartedEvent.fire(new GameStarted());
        assertThat(strategySelector.isCheckpointFired()).isFalse();

        // Checkpoint can fire again
        postureClassifiedEvent.fire(new EnemyPostureClassifiedEvent("AGGRESSIVE"));
        assertThat(strategySelector.isCheckpointFired()).isTrue();
    }
}
