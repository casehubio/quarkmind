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
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryMilestoneOutcomeRecorderTest {

    private RecordingOutcomeRecorder outcomeRecorder;
    private StubAdvisoryInvocationCounter invocationCounter;
    private GameSession gameSession;
    private AdvisoryMilestoneSession advisorySession;
    private AdvisoryMilestoneOutcomeRecorder recorder;

    @BeforeEach
    void setUp() {
        outcomeRecorder = new RecordingOutcomeRecorder();
        invocationCounter = new StubAdvisoryInvocationCounter();
        gameSession = new GameSession();
        gameSession.setCaseId(UUID.randomUUID());
        advisorySession = new AdvisoryMilestoneSession();

        recorder = new AdvisoryMilestoneOutcomeRecorder(
            outcomeRecorder,
            invocationCounter.unwrap(),
            gameSession,
            advisorySession,
            state -> new DominanceScore(0.5, Map.of()),
            List.of(new FrameThresholdTrigger(List.of(
                new FrameThresholdTrigger.Threshold(4032, 0.3)))),
            true, 0.15);
    }

    // --- game-end only (no milestones fired) ---

    @Test
    void gameEnd_victory_recordsEndorsedForEachAdvisor() {
        invocationCounter.setInvoked(Map.of(
            "claude:crisis@v1", 1000L,
            "claude:economic@v1", 2000L));
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(2);
        assertThat(outcomeRecorder.records)
            .allMatch(r -> r.verdict() == AttestationVerdict.ENDORSED)
            .allMatch(r -> r.confidence() == 1.0)
            .allMatch(r -> r.capabilityTag().equals("game-outcome"))
            .extracting(OutcomeRecord::actorId)
            .containsExactlyInAnyOrder("claude:crisis@v1", "claude:economic@v1");
    }

    @Test
    void gameEnd_defeat_recordsChallenged() {
        invocationCounter.setInvoked(Map.of("claude:strategic@v1", 1000L));
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.LOSS));

        assertThat(outcomeRecorder.records).hasSize(1);
        assertThat(outcomeRecorder.records.get(0).verdict())
            .isEqualTo(AttestationVerdict.CHALLENGED);
    }

    @Test
    void gameEnd_tie_recordsSound() {
        invocationCounter.setInvoked(Map.of("claude:crisis@v1", 1000L));
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.TIE));

        assertThat(outcomeRecorder.records.get(0).verdict())
            .isEqualTo(AttestationVerdict.SOUND);
    }

    @Test
    void gameEnd_unknown_skips() {
        invocationCounter.setInvoked(Map.of("claude:crisis@v1", 1000L));
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.UNKNOWN));

        assertThat(outcomeRecorder.records).isEmpty();
    }

    @Test
    void gameEnd_emptyInvocationSet_skips() {
        invocationCounter.setInvoked(Map.of());
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).isEmpty();
    }

    // --- milestones disabled ---

    @Test
    void evaluateMilestones_whenDisabled_isNoOp() {
        recorder = new AdvisoryMilestoneOutcomeRecorder(
            outcomeRecorder, invocationCounter.unwrap(), gameSession,
            advisorySession,
            state -> new DominanceScore(0.5, Map.of()),
            List.of(), false, 0.15);

        invocationCounter.setInvoked(Map.of("claude:crisis@v1", 1000L));
        recorder.onGameStarted(new GameStarted());

        recorder.evaluateMilestones(gameStateAtFrame(5000));

        assertThat(outcomeRecorder.attestations).isEmpty();
    }

    @Test
    void gameEnd_recordsEvenWhenMilestonesDisabled() {
        recorder = new AdvisoryMilestoneOutcomeRecorder(
            outcomeRecorder, invocationCounter.unwrap(), gameSession,
            advisorySession,
            state -> new DominanceScore(0.5, Map.of()),
            List.of(), false, 0.15);

        invocationCounter.setInvoked(Map.of("claude:crisis@v1", 1000L));
        recorder.onGameStarted(new GameStarted());
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(1);
    }

    // --- session reset ---

    @Test
    void gameStarted_resetsAdvisoryMilestoneSession() {
        advisorySession.markFired("frame:4032");
        advisorySession.setEntryId("claude:crisis@v1", UUID.randomUUID());

        recorder.onGameStarted(new GameStarted());

        assertThat(advisorySession.hasFired("frame:4032")).isFalse();
        assertThat(advisorySession.entryId("claude:crisis@v1")).isEmpty();
    }

    // --- game-end appends to existing entry when milestones fired ---

    @Test
    void gameEnd_appendsToExistingEntry_whenMilestonesFired() {
        invocationCounter.setInvoked(Map.of("claude:crisis@v1", 1000L));
        recorder.onGameStarted(new GameStarted());

        UUID entryId = UUID.randomUUID();
        advisorySession.setEntryId("claude:crisis@v1", entryId);

        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).isEmpty();
        assertThat(outcomeRecorder.attestations).hasSize(1);
        var att = outcomeRecorder.attestations.get(0);
        assertThat(att.entryId()).isEqualTo(entryId);
        assertThat(att.verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(att.confidence()).isEqualTo(1.0);
        assertThat(att.dimension()).isEqualTo("game-outcome");
    }

    // --- helpers ---

    private static GameState gameStateAtFrame(long frame) {
        return new GameState(200, 100, 15, 6, List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            frame, null);
    }

    static class RecordingOutcomeRecorder implements OutcomeRecorder {
        final List<OutcomeRecord> records = new ArrayList<>();
        final List<Attestation> attestations = new ArrayList<>();

        @Override
        public UUID record(OutcomeRecord record) {
            records.add(record);
            return UUID.randomUUID();
        }

        @Override
        public void addAttestation(UUID id, AttestationVerdict verdict,
                                   double confidence, String dimension) {
            attestations.add(new Attestation(id, verdict, confidence, dimension));
        }

        record Attestation(UUID entryId, AttestationVerdict verdict,
                           double confidence, String dimension) {}
    }

    static class StubAdvisoryInvocationCounter {
        private final ConcurrentHashMap<String, Long> invoked = new ConcurrentHashMap<>();

        void setInvoked(Map<String, Long> advisors) {
            invoked.clear();
            invoked.putAll(advisors);
        }

        AdvisoryInvocationCounter unwrap() {
            return new AdvisoryInvocationCounter() {
                @Override
                public Set<String> snapshot() {
                    return Set.copyOf(invoked.keySet());
                }

                @Override
                public OptionalLong firstFrame(String advisorId) {
                    Long frame = invoked.get(advisorId);
                    return frame != null ? OptionalLong.of(frame) : OptionalLong.empty();
                }
            };
        }
    }
}
