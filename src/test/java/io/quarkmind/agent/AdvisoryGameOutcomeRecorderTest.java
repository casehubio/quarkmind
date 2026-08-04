package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStopped;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain JUnit test for {@link AdvisoryGameOutcomeRecorder}.
 * No CDI — constructs dependencies directly via constructor injection and test doubles.
 */
class AdvisoryGameOutcomeRecorderTest {

    private RecordingOutcomeRecorder outcomeRecorder;
    private TestAdvisoryInvocationCounter invocationCounter;
    private GameSession gameSession;
    private AdvisoryGameOutcomeRecorder recorder;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        outcomeRecorder = new RecordingOutcomeRecorder();
        invocationCounter = new TestAdvisoryInvocationCounter();
        sessionId = UUID.randomUUID();
        gameSession = new GameSession();
        gameSession.setCaseId(sessionId);
        recorder = new AdvisoryGameOutcomeRecorder(outcomeRecorder, invocationCounter.unwrap(), gameSession);
    }

    @Test
    void victory_recordsEndorsedForEachAdvisor() {
        invocationCounter.setInvoked(Set.of("claude:crisis@v1", "claude:economic@v1"));
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).hasSize(2);
        assertThat(outcomeRecorder.records)
            .allMatch(r -> r.verdict() == AttestationVerdict.ENDORSED)
            .allMatch(r -> r.confidence() == 1.0)
            .allMatch(r -> r.capabilityTag().equals("game-outcome"))
            .allMatch(r -> r.subjectId().equals(sessionId))
            .extracting(OutcomeRecord::actorId)
            .containsExactlyInAnyOrder("claude:crisis@v1", "claude:economic@v1");
    }

    @Test
    void defeat_recordsChallengedForEachAdvisor() {
        invocationCounter.setInvoked(Set.of("claude:strategic@v1"));
        recorder.onGameStopped(new GameStopped(GameResult.LOSS));

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:strategic@v1");
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.CHALLENGED);
        assertThat(record.capabilityTag()).isEqualTo("game-outcome");
        assertThat(record.confidence()).isEqualTo(1.0);
        assertThat(record.subjectId()).isEqualTo(sessionId);
    }

    @Test
    void tie_recordsSoundForEachAdvisor() {
        invocationCounter.setInvoked(Set.of("claude:crisis@v1", "claude:economic@v1"));
        recorder.onGameStopped(new GameStopped(GameResult.TIE));

        assertThat(outcomeRecorder.records).hasSize(2);
        assertThat(outcomeRecorder.records)
            .allMatch(r -> r.verdict() == AttestationVerdict.SOUND)
            .allMatch(r -> r.confidence() == 1.0)
            .allMatch(r -> r.capabilityTag().equals("game-outcome"));
    }

    @Test
    void unknown_skipsAttestation() {
        invocationCounter.setInvoked(Set.of("claude:crisis@v1"));
        recorder.onGameStopped(new GameStopped(GameResult.UNKNOWN));

        assertThat(outcomeRecorder.records).isEmpty();
    }

    @Test
    void emptyInvocationSet_skipsAttestation() {
        invocationCounter.setInvoked(Set.of());
        recorder.onGameStopped(new GameStopped(GameResult.WIN));

        assertThat(outcomeRecorder.records).isEmpty();
    }

    // Test doubles

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

    /**
     * Test double for {@link AdvisoryInvocationCounter}.
     * Does not extend the real class to avoid CDI ambiguity during @QuarkusTest runs.
     */
    static class TestAdvisoryInvocationCounter {
        private final AdvisoryInvocationCounter delegate = new AdvisoryInvocationCounter();
        private Set<String> stubbedSnapshot = Set.of();

        void setInvoked(Set<String> invoked) {
            this.stubbedSnapshot = invoked;
        }

        Set<String> snapshot() {
            return stubbedSnapshot;
        }

        // Unwrap to pass to AdvisoryGameOutcomeRecorder constructor
        AdvisoryInvocationCounter unwrap() {
            return new AdvisoryInvocationCounter() {
                @Override
                public Set<String> snapshot() {
                    return stubbedSnapshot;
                }
            };
        }
    }
}
