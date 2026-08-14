package io.quarkmind.qa.workbench;

import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.coaching.CoachingAdvice;
import io.quarkmind.plugin.coaching.CoachingComplianceEvaluator;
import io.quarkmind.plugin.coaching.CoachingDomain;
import io.quarkmind.plugin.coaching.CoachingEffectivenessTrustRecorder;
import io.quarkmind.plugin.coaching.CountDelta;
import io.quarkmind.plugin.coaching.LocationResolver;
import io.quarkmind.plugin.coaching.OpenCommitment;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class CoachingAcknowledgmentHandlerTest {

    @Test
    void acknowledge_done_resolvesAndReturnsTrue() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 0), 450);
        commitments.put(CoachingDomain.MILITARY, new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        var handler = new CoachingAcknowledgmentHandler(evaluator, null, null);

        assertThat(handler.acknowledge("corr-1", true)).isTrue();
        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("ENDORSED");
        assertThat(recorder.lastAgentId).isEqualTo("worker-1");
    }

    @Test
    void acknowledge_decline_challengedAndReturnsTrue() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 0), 450);
        commitments.put(CoachingDomain.MILITARY, new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        var handler = new CoachingAcknowledgmentHandler(evaluator, null, null);

        assertThat(handler.acknowledge("corr-1", false)).isTrue();
        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("CHALLENGED");
    }

    @Test
    void acknowledge_unknownCorrelationId_returnsFalseNoDispatch() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var handler = new CoachingAcknowledgmentHandler(evaluator, null, null);

        assertThat(handler.acknowledge("nonexistent", true)).isFalse();
        assertThat(recorder.lastOutcome).isNull();
    }

    static class TestRecorder extends CoachingEffectivenessTrustRecorder {
        String              lastOutcome;
        String              lastAgentId;
        Map<String, String> outcomes = new LinkedHashMap<>();

        @Override
        public void record(String correlationId, String agentId, String outcome, CoachingAdvice advice) {
            this.lastOutcome = outcome;
            this.lastAgentId = agentId;
            this.outcomes.put(correlationId, outcome);
        }
    }
}
