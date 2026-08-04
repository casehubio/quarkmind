package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmWorkerLatencyRecorderTest {

    private TestOutcomeRecorder outcomeRecorder;
    private LlmWorkerLatencyRecorder recorder;
    private UUID gameSessionId;

    @BeforeEach
    void setUp() {
        outcomeRecorder = new TestOutcomeRecorder();
        gameSessionId = UUID.randomUUID();
        GameSession gameSession = new GameSession();
        gameSession.setCaseId(gameSessionId);
        recorder = new LlmWorkerLatencyRecorder();
        recorder.outcomeRecorder = outcomeRecorder;
        recorder.gameSession = gameSession;
    }

    @Test
    void crisis_advisory_under_threshold_scores_perfectly() {
        // Crisis max: 2000ms, actual: 500ms
        // Score: 1.0 - (500/2000) = 0.75
        var event = new LlmWorkerCompleted(
            "claude:crisis-aggressive@v1",
            "advisory-crisis",
            1000L,
            500L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:crisis-aggressive@v1");
        assertThat(record.subjectId()).isEqualTo(gameSessionId);
        assertThat(record.capabilityTag()).isEqualTo("response-latency");
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(record.confidence()).isEqualTo(0.75);
    }

    @Test
    void strategic_advisory_at_threshold_scores_minimum() {
        // Strategic max: 5000ms, actual: 5000ms
        // Score: 1.0 - (5000/5000) = 0.0 → clamped to 0.01 (API minimum)
        var event = new LlmWorkerCompleted(
            "claude:strategic-balanced@v1",
            "advisory-strategic",
            2000L,
            5000L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:strategic-balanced@v1");
        assertThat(record.confidence()).isEqualTo(0.01);
    }

    @Test
    void economic_advisory_over_threshold_clamps_to_minimum() {
        // Economic max: 4000ms, actual: 6000ms
        // Score: 1.0 - (6000/4000) = -0.5 → clamped to 0.01 (API minimum)
        var event = new LlmWorkerCompleted(
            "claude:economic-conservative@v1",
            "advisory-economic",
            3000L,
            6000L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.confidence()).isEqualTo(0.01);
    }

    @Test
    void unknown_capability_uses_default_threshold() {
        // Default max: 5000ms, actual: 1000ms
        // Score: 1.0 - (1000/5000) = 0.8
        var event = new LlmWorkerCompleted(
            "claude:tactical-unknown@v1",
            "advisory-tactical",  // not in the threshold map
            4000L,
            1000L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.confidence()).isEqualTo(0.8);
    }

    @Test
    void zero_latency_scores_perfectly() {
        // Crisis max: 2000ms, actual: 0ms
        // Score: 1.0 - (0/2000) = 1.0
        var event = new LlmWorkerCompleted(
            "claude:crisis-defensive@v1",
            "advisory-crisis",
            5000L,
            0L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.confidence()).isEqualTo(1.0);
    }

    @Test
    void reactive_commentary_under_threshold_scores_well() {
        // Reactive max: 2000ms, actual: 800ms
        // Score: 1.0 - (800/2000) = 0.6
        var event = new LlmWorkerCompleted(
            "claude:commentator-energetic@v1",
            "commentary-reactive",
            2000L,
            800L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:commentator-energetic@v1");
        assertThat(record.subjectId()).isEqualTo(gameSessionId);
        assertThat(record.capabilityTag()).isEqualTo("response-latency");
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(record.confidence()).isEqualTo(0.6);
    }

    @Test
    void narrative_commentary_at_threshold_scores_minimum() {
        // Narrative max: 5000ms, actual: 5000ms
        // Score: 1.0 - (5000/5000) = 0.0 → clamped to 0.01 (API minimum)
        var event = new LlmWorkerCompleted(
            "claude:narrator-dramatic@v1",
            "commentary-narrative",
            3000L,
            5000L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:narrator-dramatic@v1");
        assertThat(record.confidence()).isEqualTo(0.01);
    }

    @Test
    void narrative_commentary_under_threshold_scores_well() {
        // Narrative max: 5000ms, actual: 1500ms
        // Score: 1.0 - (1500/5000) = 0.7
        var event = new LlmWorkerCompleted(
            "claude:narrator-tactical@v1",
            "commentary-narrative",
            4000L,
            1500L
        );

        recorder.onLlmWorkerCompleted(event);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:narrator-tactical@v1");
        assertThat(record.confidence()).isEqualTo(0.7);
    }

    static class TestOutcomeRecorder implements OutcomeRecorder {
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
