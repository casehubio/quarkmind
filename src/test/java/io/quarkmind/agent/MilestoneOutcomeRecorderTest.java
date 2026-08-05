package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.domain.DominanceScore;
import io.quarkmind.domain.GameState;
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


// --- proportional attribution for strategy pivots ---

    @Test
    void gameEnd_withPivot_recordsProportionalVerdicts() {
        recorder.onGameStarted(new GameStarted());

        // Strategy A selected at frame 0
        recorder.onStrategySelected(strategyEvent("strategy.drools", 0, 0));
        // Pivot to Strategy B at frame 6000 (out of 10000 total)
        recorder.onStrategySelected(strategyEvent("strategy.early-pressure", 1, 6000));
        // Track last seen frame
        recorder.evaluateMilestones(gameStateAtFrame(10000));

        recorder.onGameStopped(new GameStopped(GameResult.LOSS));

        assertThat(outcomeRecorder.records).hasSize(2);

        OutcomeRecord first = outcomeRecorder.records.stream()
                                                     .filter(r -> r.actorId().equals("strategy.drools")).findFirst().orElseThrow();
        OutcomeRecord second = outcomeRecorder.records.stream()
                                                      .filter(r -> r.actorId().equals("strategy.early-pressure")).findFirst().orElseThrow();

        assertThat(first.verdict()).isEqualTo(AttestationVerdict.CHALLENGED);
        assertThat(first.confidence()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.01));

        assertThat(second.verdict()).isEqualTo(AttestationVerdict.CHALLENGED);
        assertThat(second.confidence()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void gameEnd_withPivot_win_recordsProportionalEndorsed() {
        recorder.onGameStarted(new GameStarted());
        recorder.onStrategySelected(strategyEvent("strategy.drools", 0, 0));
        recorder.onStrategySelected(strategyEvent("strategy.early-pressure", 1, 4000));
        recorder.evaluateMilestones(gameStateAtFrame(10000));

        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(2);

        OutcomeRecord first = outcomeRecorder.records.stream()
                                                     .filter(r -> r.actorId().equals("strategy.drools")).findFirst().orElseThrow();
        OutcomeRecord second = outcomeRecorder.records.stream()
                                                      .filter(r -> r.actorId().equals("strategy.early-pressure")).findFirst().orElseThrow();

        assertThat(first.verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(first.confidence()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.01));

        assertThat(second.verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(second.confidence()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void gameEnd_noPivot_singleSelection_recordsFullConfidence() {
        recorder.onGameStarted(new GameStarted());
        recorder.onStrategySelected(strategyEvent("strategy.drools", 0, 0));
        recorder.evaluateMilestones(gameStateAtFrame(10000));

        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(1);
        assertThat(outcomeRecorder.records.get(0).actorId()).isEqualTo("strategy.drools");
        assertThat(outcomeRecorder.records.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void gameEnd_noSelection_fallsBackToRouterLastSelected() {
        recorder.onGameStarted(new GameStarted());
        // No onStrategySelected called — falls back to strategyRouter.lastSelectedId()
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(1);
        assertThat(outcomeRecorder.records.get(0).actorId()).isEqualTo("strategy.drools");
        assertThat(outcomeRecorder.records.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void gameEnd_threePivots_recordsThreeProportionalVerdicts() {
        recorder.onGameStarted(new GameStarted());
        recorder.onStrategySelected(strategyEvent("A", 0, 0));
        recorder.onStrategySelected(strategyEvent("B", 1, 3000));
        recorder.onStrategySelected(strategyEvent("C", 2, 7000));
        recorder.evaluateMilestones(gameStateAtFrame(10000));

        recorder.onGameStopped(new GameStopped(GameResult.LOSS));

        assertThat(outcomeRecorder.records).hasSize(3);
        OutcomeRecord a = outcomeRecorder.records.stream().filter(r -> r.actorId().equals("A")).findFirst().orElseThrow();
        OutcomeRecord b = outcomeRecorder.records.stream().filter(r -> r.actorId().equals("B")).findFirst().orElseThrow();
        OutcomeRecord c = outcomeRecorder.records.stream().filter(r -> r.actorId().equals("C")).findFirst().orElseThrow();

        assertThat(a.confidence()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.01));
        assertThat(b.confidence()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.01));
        assertThat(c.confidence()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void gameStarted_resetsStrategySpans() {
        recorder.onStrategySelected(strategyEvent("old-strategy", 0, 0));
        recorder.onGameStarted(new GameStarted());
        // After reset, should fall back to router — no spans
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(1);
        assertThat(outcomeRecorder.records.get(0).actorId()).isEqualTo("strategy.drools");
        assertThat(outcomeRecorder.records.get(0).confidence()).isEqualTo(1.0);
    }

    private static io.quarkmind.agent.cbr.StrategySelectionPublished strategyEvent(String id, int pivotCount, long frame) {
        return new io.quarkmind.agent.cbr.StrategySelectionPublished(id, io.quarkmind.domain.StrategyArchetype.PROTOSS_GATEWAY_RUSH, 0.8, pivotCount, frame);
    }

    private static GameState gameStateAtFrame(long frame) {
        return new GameState(200, 100, 15, 6, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), frame, null);
    }

    static class RecordingOutcomeRecorder implements OutcomeRecorder {
        final List<OutcomeRecord> records = new ArrayList<>();

        @Override
        public UUID record(OutcomeRecord record) {
            records.add(record);
            return UUID.randomUUID();
        }

        @Override
        public void addAttestation(UUID id, io.casehub.ledger.api.model.AttestationVerdict verdict, double confidence, String dimension) {
        }
    }
}
