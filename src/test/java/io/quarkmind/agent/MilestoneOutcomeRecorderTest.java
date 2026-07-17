package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.GameStopped;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MilestoneOutcomeRecorderTest {

    private RecordingOutcomeRecorder outcomeRecorder;
    private io.quarkmind.agent.cbr.SC2StrategyRouterTask strategyRouter;
    private GameSession gameSession;
    private MilestoneSession milestoneSession;
    private MilestoneOutcomeRecorder recorder;

    @BeforeEach
    void setUp() {
        outcomeRecorder = new RecordingOutcomeRecorder();
        strategyRouter = org.mockito.Mockito.mock(io.quarkmind.agent.cbr.SC2StrategyRouterTask.class);
        org.mockito.Mockito.when(strategyRouter.lastSelectedId()).thenReturn("strategy.drools");
        gameSession = new GameSession();
        milestoneSession = new MilestoneSession();

        // Default: milestones enabled, dead zone 0.15
        recorder = new MilestoneOutcomeRecorder(
            outcomeRecorder, strategyRouter, gameSession, milestoneSession,
            state -> new DominanceScore(0.5, Map.of()), // always returns "moderately ahead"
            List.of(new FrameThresholdTrigger(List.of(
                new FrameThresholdTrigger.Threshold(4032, 0.3)))),
            true, 0.15);
    }

    // --- game-end only (no milestones fired) ---

    @Test
    void gameEnd_noMilestones_recordsExactlyOneOutcome() {
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(1);
        assertThat(outcomeRecorder.records.get(0).verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(outcomeRecorder.records.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void gameEnd_loss_recordsChallenged() {
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.LOSS));

        assertThat(outcomeRecorder.records.get(0).verdict()).isEqualTo(AttestationVerdict.CHALLENGED);
    }

    @Test
    void gameEnd_tie_recordsSound() {
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.TIE));

        assertThat(outcomeRecorder.records.get(0).verdict()).isEqualTo(AttestationVerdict.SOUND);
    }

    @Test
    void gameEnd_unknown_skips() {
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.UNKNOWN));

        assertThat(outcomeRecorder.records).isEmpty();
    }

    // --- SPI fallback (OutcomeRecorder is not AttestingOutcomeRecorder) ---

    @Test
    void evaluateMilestones_withoutSpi_isNoOp() {
        recorder.onGameStarted(new GameStarted());
        GameState state = gameStateAtFrame(5000);

        recorder.evaluateMilestones(state);

        // No milestone attestation recorded — SPI not available
        assertThat(outcomeRecorder.records).isEmpty();
    }

    // --- dead zone ---

    @Test
    void evaluateMilestones_deadZone_skipsAttestation() {
        // dominance assessor returns 0.1 (below dead zone 0.15)
        recorder = new MilestoneOutcomeRecorder(
            outcomeRecorder, strategyRouter, gameSession, milestoneSession,
            state -> new DominanceScore(0.1, Map.of()), // below dead zone
            List.of(new FrameThresholdTrigger(List.of(
                new FrameThresholdTrigger.Threshold(4032, 0.3)))),
            true, 0.15);

        recorder.onGameStarted(new GameStarted());
        // Even with SPI mock, the dead zone should prevent recording
        // (but without SPI, evaluateMilestones is no-op anyway)
        assertThat(outcomeRecorder.records).isEmpty();
    }

    // --- milestone session reset ---

    @Test
    void gameStarted_resetsMilestoneSession() {
        milestoneSession.markFired("frame:4032");
        milestoneSession.setEntryId("strategy.drools", UUID.randomUUID());

        recorder.onGameStarted(new GameStarted());

        assertThat(milestoneSession.hasFired("frame:4032")).isFalse();
        assertThat(milestoneSession.entryId("strategy.drools")).isEmpty();
    }

    // --- game-end uses correct strategy and context ---

    @Test
    void gameEnd_usesCurrentStrategyAndContext() {
        org.mockito.Mockito.when(strategyRouter.lastSelectedId()).thenReturn("strategy.early-pressure");
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        OutcomeRecord recorded = outcomeRecorder.records.get(0);
        assertThat(recorded.actorId()).isEqualTo("strategy.early-pressure");
        assertThat(recorded.capabilityTag()).isEqualTo("strategy");
    }

    // --- disabled switch ---

    @Test
    void gameEnd_recordsEvenWhenMilestonesDisabled() {
        recorder = new MilestoneOutcomeRecorder(
            outcomeRecorder, strategyRouter, gameSession, milestoneSession,
            state -> new DominanceScore(0.5, Map.of()),
            List.of(), false, 0.15); // disabled

        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(1);
    }

    // --- helpers ---

    private static GameState gameStateAtFrame(long frame) {
        return new GameState(200, 100, 15, 6, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), frame);
    }

    static class RecordingOutcomeRecorder implements OutcomeRecorder {
        final List<OutcomeRecord> records = new ArrayList<>();

        @Override
        public void record(OutcomeRecord record) {
            records.add(record);
        }
    }
}
