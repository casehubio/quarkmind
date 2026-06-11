package io.quarkmind.sc2.mock;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.memory.InMemoryLedgerEntryRepository;
import io.casehub.ledger.memory.InMemoryActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.StrategySelector;
import io.quarkmind.sc2.GameResult;
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
        trustScoreRepo.clear();
        strategySelector.reset();
        gameSession.reset();
    }

    // -----------------------------------------------------------------------
    // Existing accumulation behaviour — fires WIN (ENDORSED writes an entry)
    // -----------------------------------------------------------------------

    @Test
    void gameStopped_writesOutcomeRecord_andMaterializesDecisionCount() {
        gameStartedEvent.fire(new GameStarted());
        String selectedId = strategySelector.getSelectedId();
        String context    = strategySelector.getOpponentContext();

        assertThat(selectedId).isEqualTo("strategy.drools");
        assertThat(context).isEqualTo(QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);

        gameStoppedEvent.fire(new GameStopped(GameResult.WIN));

        assertThat(ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID))
            .as("ledger should have at least one entry after game stop")
            .isNotEmpty();
        assertThat(trustGateService.decisionCount(selectedId, context))
            .as("decisionCount should be 1 after one recorded game outcome")
            .isEqualTo(1);
    }

    @Test
    void gameStopped_recordsCorrectStrategyAndContext() {
        gameStartedEvent.fire(new GameStarted());
        String selectedId = strategySelector.getSelectedId();
        String context    = strategySelector.getOpponentContext();

        gameStoppedEvent.fire(new GameStopped(GameResult.WIN));

        assertThat(trustGateService.decisionCount(selectedId, context)).isEqualTo(1);
        assertThat(trustGateService.decisionCount("strategy.early-pressure",
            QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE)).isZero();
    }

    @Test
    void gameStopped_acrossMultipleGames_accumulatesDecisionCount() {
        gameStartedEvent.fire(new GameStarted());
        gameStoppedEvent.fire(new GameStopped(GameResult.WIN));

        gameSession.reset();
        gameStartedEvent.fire(new GameStarted());
        gameStoppedEvent.fire(new GameStopped(GameResult.WIN));

        assertThat(trustGateService.decisionCount(
            "strategy.drools", QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN)).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Verdict mapping: WIN → ENDORSED, LOSS → CHALLENGED, TIE → SOUND
    // -----------------------------------------------------------------------

    @Test
    void gameStopped_withWin_writesEndorsedVerdict() {
        gameStartedEvent.fire(new GameStarted());

        gameStoppedEvent.fire(new GameStopped(GameResult.WIN));

        var entries = ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(entries).isNotEmpty();
        var attestations = ledgerRepo.findAttestationsByEntryId(
            entries.get(0).id, TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(attestations).isNotEmpty();
        assertThat(attestations.get(0).verdict)
            .as("WIN must produce ENDORSED verdict")
            .isEqualTo(AttestationVerdict.ENDORSED);
    }

    @Test
    void gameStopped_withLoss_writesChallengedVerdict() {
        gameStartedEvent.fire(new GameStarted());

        gameStoppedEvent.fire(new GameStopped(GameResult.LOSS));

        var entries = ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(entries).isNotEmpty();
        var attestations = ledgerRepo.findAttestationsByEntryId(
            entries.get(0).id, TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(attestations).isNotEmpty();
        assertThat(attestations.get(0).verdict)
            .as("LOSS must produce CHALLENGED verdict")
            .isEqualTo(AttestationVerdict.CHALLENGED);
    }

    @Test
    void gameStopped_withTie_writesSoundVerdict() {
        gameStartedEvent.fire(new GameStarted());

        gameStoppedEvent.fire(new GameStopped(GameResult.TIE));

        var entries = ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(entries).isNotEmpty();
        var attestations = ledgerRepo.findAttestationsByEntryId(
            entries.get(0).id, TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(attestations).isNotEmpty();
        assertThat(attestations.get(0).verdict)
            .as("TIE must produce SOUND verdict")
            .isEqualTo(AttestationVerdict.SOUND);
    }

    @Test
    void gameStopped_withUnknown_writesNoLedgerEntry_andDoesNotChangeTrustCount() {
        gameStartedEvent.fire(new GameStarted());
        String selectedId = strategySelector.getSelectedId();
        String context    = strategySelector.getOpponentContext();

        gameStoppedEvent.fire(new GameStopped(GameResult.UNKNOWN));

        assertThat(ledgerRepo.findBySubjectId(gameSession.id(), TenancyConstants.DEFAULT_TENANT_ID))
            .as("UNKNOWN must not write any ledger entry")
            .isEmpty();
        assertThat(trustGateService.decisionCount(selectedId, context))
            .as("UNKNOWN must not affect decisionCount")
            .isZero();
    }
}
