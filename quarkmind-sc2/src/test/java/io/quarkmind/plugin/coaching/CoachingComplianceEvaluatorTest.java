package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class CoachingComplianceEvaluatorTest {

    @Test
    void implicitCompliance_unitCountDeltaSatisfied_endorsed() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 2), 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 5));
        evaluator.evaluate(state, 350);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("ENDORSED");
    }

    @Test
    void implicitCompliance_notSatisfied_withinWindow_noAction() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 2), 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 3));
        evaluator.evaluate(state, 250);

        assertThat(commitments).containsKey(CoachingDomain.MILITARY);
        assertThat(recorder.lastOutcome).isNull();
    }

    @Test
    void implicitCompliance_notSatisfied_pastAutoExpire_challenged() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver(), 900);

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 2), 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 3));
        evaluator.evaluate(state, 1050);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("CHALLENGED");
    }

    @Test
    void nonVerifiable_autoExpiresAsNeutral() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("improve macro", CoachingDomain.BUILD,
            null, 450);
        commitments.put(CoachingDomain.BUILD,
            new OpenCommitment("corr-2", "worker-1", advice, 100, null));

        var state = gameStateWithUnits(Map.of());
        evaluator.evaluate(state, 600);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("NEUTRAL");
    }

    @Test
    void buildingType_compliance_endorsed() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("expand", CoachingDomain.EXPAND,
            new CountDelta(null, BuildingType.NEXUS, 1, 1), 200);
        commitments.put(CoachingDomain.EXPAND,
            new OpenCommitment("corr-3", "worker-1", advice, 100, null));

        var state = gameStateWithBuildings(Map.of(BuildingType.NEXUS, 2));
        evaluator.evaluate(state, 350);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("ENDORSED");
    }

    @Test
    void withdrawAll_clearsAndRecordsNeutral() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 2), 450);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        evaluator.withdrawAll();

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("NEUTRAL");
    }

    @Test
    void multipleCommitments_evaluatedIndependently() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var militaryAdvice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 2), 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", "worker-1", militaryAdvice, 100, null));

        var expandAdvice = new CoachingAdvice("improve macro", CoachingDomain.EXPAND,
            null, 200);
        commitments.put(CoachingDomain.EXPAND,
            new OpenCommitment("corr-2", "worker-1", expandAdvice, 100, null));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 5));
        evaluator.evaluate(state, 350);

        assertThat(commitments).isEmpty();
        assertThat(recorder.outcomes).containsEntry("corr-1", "ENDORSED");
        assertThat(recorder.outcomes).containsEntry("corr-2", "NEUTRAL");
    }

    @Test
    void resolveHuman_done_endorsedAndRemoved() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder    = new TestTrustRecorder();
        var evaluator   = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        new CountDelta(UnitType.STALKER, null, 3, 0), 450);
        commitments.put(CoachingDomain.MILITARY, new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        boolean result = evaluator.resolveHuman("corr-1", true);

        assertThat(result).isTrue();
        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("ENDORSED");
        assertThat(recorder.lastAgentId).isEqualTo("worker-1");
    }

    @Test
    void resolveHuman_decline_challengedAndRemoved() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder    = new TestTrustRecorder();
        var evaluator   = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        new CountDelta(UnitType.STALKER, null, 3, 0), 450);
        commitments.put(CoachingDomain.MILITARY, new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        assertThat(evaluator.resolveHuman("corr-1", false)).isTrue();
        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("CHALLENGED");
    }

    @Test
    void resolveHuman_unknownCorrelationId_returnsFalse() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder    = new TestTrustRecorder();
        var evaluator   = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver());

        assertThat(evaluator.resolveHuman("nonexistent", true)).isFalse();
        assertThat(recorder.lastOutcome).isNull();
    }

    @Test
    void nonVerifiable_withBaselineAndDispatcher_dispatchesLlm() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder    = new TestTrustRecorder();
        var dispatcher  = new TestDispatcher();
        var evaluator   = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver(), dispatcher);

        var advice = new CoachingAdvice("Improve your macro", CoachingDomain.BUILD, null, 200);
        var baselineState = new GameState(400, 200, 46, 38, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 100, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        commitments.put(CoachingDomain.BUILD, new OpenCommitment("corr-1", "worker-1", advice, 100, baselineState));

        var currentState = gameStateWithUnits(Map.of());
        evaluator.evaluate(currentState, 400);

        assertThat(commitments).isEmpty();
        assertThat(dispatcher.dispatched).isTrue();
        assertThat(dispatcher.lastCorrelationId).isEqualTo("corr-1");
        assertThat(recorder.lastOutcome).isNull();
    }

    @Test
    void nonVerifiable_withoutBaseline_degradesToNeutral() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder    = new TestTrustRecorder();
        var dispatcher  = new TestDispatcher();
        var evaluator   = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver(), dispatcher);

        var advice = new CoachingAdvice("Improve your macro", CoachingDomain.BUILD, null, 200);
        commitments.put(CoachingDomain.BUILD, new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        var currentState = gameStateWithUnits(Map.of());
        evaluator.evaluate(currentState, 400);

        assertThat(commitments).isEmpty();
        assertThat(dispatcher.dispatched).isFalse();
        assertThat(recorder.lastOutcome).isEqualTo("NEUTRAL");
    }

    @Test
    void withdrawAll_callsCancelAllOnDispatcher() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder    = new TestTrustRecorder();
        var dispatcher  = new TestDispatcher();
        var evaluator   = new CoachingComplianceEvaluator(commitments, recorder, new LocationResolver(), dispatcher);

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
                                        new CountDelta(UnitType.STALKER, null, 3, 2), 450);
        commitments.put(CoachingDomain.MILITARY,
                        new OpenCommitment("corr-1", "worker-1", advice, 100, null));

        evaluator.withdrawAll();

        assertThat(commitments).isEmpty();
        assertThat(dispatcher.cancelled).isTrue();
    }

    static class TestDispatcher extends ComplianceWorkerDispatcher {
        boolean dispatched;
        boolean cancelled;
        String  lastCorrelationId;

        @Override
        public boolean isAvailable() {return true;}

        @Override
        public void dispatch(OpenCommitment commitment, GameState currentState) {
            dispatched        = true;
            lastCorrelationId = commitment.correlationId();
        }

        @Override
        public void cancelAll() {
            cancelled = true;
        }
    }


    private GameState gameStateWithUnits(Map<UnitType, Integer> unitCounts) {
        List<Unit> units = new ArrayList<>();
        int tag = 1;
        for (var entry : unitCounts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                units.add(new Unit("u" + tag++, entry.getKey(), new Point2d(0f, 0f),
                    100, 100, 50, 50, 0, 0));
            }
        }
        return new GameState(400, 200, 62, 44, units, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 350, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }

    private GameState gameStateWithBuildings(Map<BuildingType, Integer> buildingCounts) {
        List<Building> buildings = new ArrayList<>();
        int tag = 1;
        for (var entry : buildingCounts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                buildings.add(new Building("b" + tag++, entry.getKey(), new Point2d(0f, 0f),
                    1000, 1000, true));
            }
        }
        return new GameState(400, 200, 62, 44, List.of(), buildings, List.of(), List.of(), List.of(), List.of(), List.of(), 350, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }

    static class TestTrustRecorder extends CoachingEffectivenessTrustRecorder {
        String lastOutcome;
        String lastAgentId;
        Map<String, String> outcomes = new LinkedHashMap<>();
        @Override
        public void record(String correlationId, String agentId, String outcome, CoachingAdvice advice) {
            this.lastOutcome = outcome;
            this.lastAgentId = agentId;
            this.outcomes.put(correlationId, outcome);
        }
    }
}
