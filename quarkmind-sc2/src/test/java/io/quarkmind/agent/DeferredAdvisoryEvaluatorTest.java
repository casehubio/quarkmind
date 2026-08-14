package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.agency.context.MapCaseContext;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeferredAdvisoryEvaluatorTest {

    private TestOutcomeRecorder outcomeRecorder;
    private DeferredAdvisoryEvaluator evaluator;
    private UUID gameSessionId;
    private GameSession gameSession;

    @BeforeEach
    void setUp() {
        outcomeRecorder = new TestOutcomeRecorder();
        gameSessionId = UUID.randomUUID();
        gameSession = new GameSession();
        gameSession.setCaseId(gameSessionId);
        evaluator = new DeferredAdvisoryEvaluator();
        evaluator.outcomeRecorder = outcomeRecorder;
        evaluator.gameSession = gameSession;
    }

    @Test
    void advisory_not_mature_yet_does_not_evaluate() {
        // Advisory at frame 1000, current frame 1100 (delta = 100 < 200 threshold)
        var event = new AdvisoryCompleted(
            "claude:strategic-balanced@v1",
            "advisory-strategic",
            1000L,
            "Expand economy",
            0.85,
            1500L,
            Map.of("minerals", 500.0, "supply", 40.0, "army", 15.0)
        );
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MINERALS, 500,
            QuarkMindCaseFile.SUPPLY_USED, 40,
            QuarkMindCaseFile.ARMY, 15
                                                   ));

        evaluator.onAdvisoryCompleted(event);
        evaluator.evaluate(ctx, 1100L);  // 100 frames later

        assertThat(outcomeRecorder.records).isEmpty();
    }

    @Test
    void advisory_mature_with_positive_delta_endorsed() {
        // Advisory at frame 1000, metrics: minerals=500, supply=40, army=15
        var event = new AdvisoryCompleted(
            "claude:economic-conservative@v1",
            "advisory-economic",
            1000L,
            "Build workers",
            0.90,
            2000L,
            Map.of("minerals", 500.0, "supply", 40.0, "army", 15.0)
        );

        evaluator.onAdvisoryCompleted(event);

        // 200 frames later: metrics improved (minerals +200, supply +10, army +5)
        CaseContext currentCtx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MINERALS, 700,
            QuarkMindCaseFile.SUPPLY_USED, 50,
            QuarkMindCaseFile.ARMY, 20
        ));
        evaluator.evaluate(currentCtx, 1200L);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:economic-conservative@v1");
        assertThat(record.subjectId()).isEqualTo(gameSessionId);
        assertThat(record.capabilityTag()).isEqualTo("recommendation-quality");
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.ENDORSED);
        assertThat(record.confidence()).isEqualTo(0.90);  // from advisory's confidence
    }

    @Test
    void advisory_mature_with_negative_delta_challenged() {
        // Advisory at frame 2000, metrics: minerals=800, supply=60, army=30
        var event = new AdvisoryCompleted(
            "claude:crisis-aggressive@v1",
            "advisory-crisis",
            2000L,
            "Defend now",
            0.95,
            800L,
            Map.of("minerals", 800.0, "supply", 60.0, "army", 30.0)
        );

        evaluator.onAdvisoryCompleted(event);

        // 200 frames later: metrics declined (minerals -300, supply -10, army -10)
        CaseContext currentCtx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MINERALS, 500,
            QuarkMindCaseFile.SUPPLY_USED, 50,
            QuarkMindCaseFile.ARMY, 20
        ));
        evaluator.evaluate(currentCtx, 2200L);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.actorId()).isEqualTo("claude:crisis-aggressive@v1");
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.CHALLENGED);
        assertThat(record.confidence()).isEqualTo(0.7);  // fixed 0.7 for CHALLENGED
    }

    @Test
    void multiple_advisories_evaluated_independently() {
        // Advisory 1 at frame 1000 (will be mature at 1200)
        var event1 = new AdvisoryCompleted(
            "claude:strategic-balanced@v1",
            "advisory-strategic",
            1000L,
            "Tech up",
            0.85,
            3000L,
            Map.of("minerals", 400.0, "supply", 30.0, "army", 10.0)
        );
        evaluator.onAdvisoryCompleted(event1);

        // Advisory 2 at frame 1100 (will be mature at 1300)
        var event2 = new AdvisoryCompleted(
            "claude:economic-aggressive@v1",
            "advisory-economic",
            1100L,
            "Expand",
            0.88,
            2500L,
            Map.of("minerals", 600.0, "supply", 40.0, "army", 15.0)
        );
        evaluator.onAdvisoryCompleted(event2);

        // Frame 1200: only advisory 1 is mature, improved metrics
        CaseContext currentCtx1 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MINERALS, 600,
            QuarkMindCaseFile.SUPPLY_USED, 45,
            QuarkMindCaseFile.ARMY, 18
        ));
        evaluator.evaluate(currentCtx1, 1200L);

        assertThat(outcomeRecorder.records).hasSize(1);
        assertThat(outcomeRecorder.records.get(0).actorId()).isEqualTo("claude:strategic-balanced@v1");
        assertThat(outcomeRecorder.records.get(0).verdict()).isEqualTo(AttestationVerdict.ENDORSED);

        // Frame 1300: advisory 2 is now mature, declined metrics
        CaseContext currentCtx2 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MINERALS, 500,
            QuarkMindCaseFile.SUPPLY_USED, 38,
            QuarkMindCaseFile.ARMY, 12
        ));
        evaluator.evaluate(currentCtx2, 1300L);

        assertThat(outcomeRecorder.records).hasSize(2);
        assertThat(outcomeRecorder.records.get(1).actorId()).isEqualTo("claude:economic-aggressive@v1");
        assertThat(outcomeRecorder.records.get(1).verdict()).isEqualTo(AttestationVerdict.CHALLENGED);
    }

    @Test
    void game_started_clears_pending_evaluations() {
        // Add a pending evaluation
        var event = new AdvisoryCompleted(
            "claude:strategic-balanced@v1",
            "advisory-strategic",
            1000L,
            "Recommendation",
            0.85,
            2000L,
            Map.of("minerals", 500.0, "supply", 40.0, "army", 15.0)
        );
        evaluator.onAdvisoryCompleted(event);

        // Fire GameStarted
        evaluator.onGameStarted(new GameStarted());

        // Now evaluate at mature frame — should not record (list was cleared)
        CaseContext currentCtx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MINERALS, 700,
            QuarkMindCaseFile.SUPPLY_USED, 50,
            QuarkMindCaseFile.ARMY, 20
        ));
        evaluator.evaluate(currentCtx, 1200L);

        assertThat(outcomeRecorder.records).isEmpty();
    }

    @Test
    void missing_metrics_in_current_context_treats_as_zero() {
        // Advisory at frame 1000, metrics: minerals=500, supply=40, army=15
        var event = new AdvisoryCompleted(
            "claude:economic-conservative@v1",
            "advisory-economic",
            1000L,
            "Build workers",
            0.90,
            2000L,
            Map.of("minerals", 500.0, "supply", 40.0, "army", 15.0)
        );

        evaluator.onAdvisoryCompleted(event);

        // 200 frames later: context has missing metrics (treated as 0)
        CaseContext currentCtx = new MapCaseContext(Map.of());
        evaluator.evaluate(currentCtx, 1200L);

        assertThat(outcomeRecorder.records).hasSize(1);
        OutcomeRecord record = outcomeRecorder.records.get(0);
        assertThat(record.verdict()).isEqualTo(AttestationVerdict.CHALLENGED);  // all deltas negative
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
