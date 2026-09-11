package io.quarkmind.qa.workbench;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.agent.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommentaryFeedbackHandlerTest {

    private TestOutcomeRecorder outcomeRecorder;
    private CommentaryFeedbackHandler handler;
    private UUID gameSessionId;

    @BeforeEach
    void setUp() {
        outcomeRecorder = new TestOutcomeRecorder();
        gameSessionId = UUID.randomUUID();
        GameSession session = new GameSession();
        session.setCaseId(gameSessionId);
        handler = new CommentaryFeedbackHandler();
        handler.outcomeRecorder = outcomeRecorder;
        handler.gameSession = session;
    }

    @Test
    void positiveFeedback_recordsEndorsedWithFullConfidence() {
        handler.recordFeedback("claude:narrator-reactive@v1", "timing-quality", true);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:narrator-reactive@v1");
        assertThat(record.subjectId()).isEqualTo(gameSessionId);
        assertThat(record.capabilityTag()).isEqualTo("timing-quality");
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(record.confidence()).isEqualTo(1.0);
    }

    @Test
    void negativeFeedback_recordsChallengedWithZeroConfidence() {
        handler.recordFeedback("claude:narrator-reactive@v1", "accuracy", false);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.capabilityTag()).isEqualTo("accuracy");
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.CHALLENGED);
        assertThat(record.confidence()).isEqualTo(0.01);
    }

    @Test
    void bothDimensions_recordedIndependently() {
        handler.recordFeedback("claude:narrator-reactive@v1", "timing-quality", true);
        handler.recordFeedback("claude:narrator-reactive@v1", "accuracy", false);

        assertThat(outcomeRecorder.records).hasSize(2);
        assertThat(outcomeRecorder.records.get(0).capabilityTag()).isEqualTo("timing-quality");
        assertThat(outcomeRecorder.records.get(1).capabilityTag()).isEqualTo("accuracy");
    }

    static class TestOutcomeRecorder implements OutcomeRecorder {
        final List<OutcomeRecord> records = new ArrayList<>();

        @Override
        public UUID record(OutcomeRecord record) {
            records.add(record);
            return UUID.randomUUID();
        }

        @Override
        public UUID record(OutcomeRecord record, String source) {
            return record(record);
        }

        @Override
        public void addAttestation(UUID id, AttestationVerdict verdict, double confidence, String dimension) {}
    }
}
