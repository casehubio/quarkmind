package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class LlmComplianceIT {

    @Inject
    CoachingComplianceEvaluator evaluator;
    @Inject
    CoachingChannelBroker broker;

    @Test
    void nonVerifiableAdvice_baselineCaptured_evaluatorRemovesCommitment() {
        var advice = new CoachingAdvice("Improve your macro", CoachingDomain.BUILD, null, 200);
        var triggerState = new GameState(400, 200, 46, 38, List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), 100, null);

        broker.onCoachingCompleted(new CoachingCompleted(
            "test-worker", "coaching", 100, advice,
            CoachingUrgencyTier.ECONOMIC, 50, triggerState));

        var commitments = broker.commitments();
        assertThat(commitments).hasSize(1);

        var commitment = commitments.values().iterator().next();
        assertThat(commitment.baselineState()).isNotNull();
        assertThat(commitment.baselineState().minerals()).isEqualTo(400);

        var currentState = new GameState(300, 150, 54, 44,
            List.of(new Unit("u1", UnitType.STALKER, new Point2d(10, 10), 100, 100, 50, 50, 0, 0)),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 400, null);

        evaluator.evaluate(currentState, 400);

        assertThat(commitments).isEmpty();
    }

    @Test
    void verifiableAdvice_baselineNotCaptured() {
        var advice = new CoachingAdvice("Build 3 stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 0), 200);
        var triggerState = new GameState(400, 200, 46, 38, List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), 200, null);

        broker.onCoachingCompleted(new CoachingCompleted(
            "test-worker", "coaching", 200, advice,
            CoachingUrgencyTier.ECONOMIC, 50, triggerState));

        var commitments = broker.commitments();
        assertThat(commitments).hasSize(1);

        var commitment = commitments.values().iterator().next();
        assertThat(commitment.baselineState()).isNull();
    }
}
