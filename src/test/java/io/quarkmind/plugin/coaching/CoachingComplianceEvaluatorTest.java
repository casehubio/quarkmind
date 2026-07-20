package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;

class CoachingComplianceEvaluatorTest {

    @Test
    void implicitCompliance_unitCountDeltaSatisfied_endorsed() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder);

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            UnitType.STALKER, null, 3, 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", advice, 100, 2));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 5));
        evaluator.evaluate(state, 350);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("ENDORSED");
    }

    @Test
    void implicitCompliance_notSatisfied_withinWindow_noAction() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder);

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            UnitType.STALKER, null, 3, 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", advice, 100, 2));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 3));
        evaluator.evaluate(state, 250);

        assertThat(commitments).containsKey(CoachingDomain.MILITARY);
        assertThat(recorder.lastOutcome).isNull();
    }

    @Test
    void implicitCompliance_notSatisfied_pastAutoExpire_challenged() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder, 900);

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            UnitType.STALKER, null, 3, 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", advice, 100, 2));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 3));
        evaluator.evaluate(state, 1050);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("CHALLENGED");
    }

    @Test
    void nonVerifiable_autoExpiresAsNeutral() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder);

        var advice = new CoachingAdvice("improve macro", CoachingDomain.BUILD,
            null, null, null, 450);
        commitments.put(CoachingDomain.BUILD,
            new OpenCommitment("corr-2", advice, 100, 0));

        var state = gameStateWithUnits(Map.of());
        evaluator.evaluate(state, 600);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("NEUTRAL");
    }

    @Test
    void buildingType_compliance_endorsed() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder);

        var advice = new CoachingAdvice("expand", CoachingDomain.EXPAND,
            null, BuildingType.NEXUS, 1, 200);
        commitments.put(CoachingDomain.EXPAND,
            new OpenCommitment("corr-3", advice, 100, 1));

        var state = gameStateWithBuildings(Map.of(BuildingType.NEXUS, 2));
        evaluator.evaluate(state, 350);

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("ENDORSED");
    }

    @Test
    void withdrawAll_clearsAndRecordsNeutral() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder);

        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            UnitType.STALKER, null, 3, 450);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", advice, 100, 2));

        evaluator.withdrawAll();

        assertThat(commitments).isEmpty();
        assertThat(recorder.lastOutcome).isEqualTo("NEUTRAL");
    }

    @Test
    void multipleCommitments_evaluatedIndependently() {
        var commitments = new ConcurrentHashMap<CoachingDomain, OpenCommitment>();
        var recorder = new TestTrustRecorder();
        var evaluator = new CoachingComplianceEvaluator(commitments, recorder);

        var militaryAdvice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            UnitType.STALKER, null, 3, 200);
        commitments.put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-1", militaryAdvice, 100, 2));

        var expandAdvice = new CoachingAdvice("improve macro", CoachingDomain.EXPAND,
            null, null, null, 200);
        commitments.put(CoachingDomain.EXPAND,
            new OpenCommitment("corr-2", expandAdvice, 100, 0));

        var state = gameStateWithUnits(Map.of(UnitType.STALKER, 5));
        evaluator.evaluate(state, 350);

        assertThat(commitments).isEmpty();
        assertThat(recorder.outcomes).containsEntry("corr-1", "ENDORSED");
        assertThat(recorder.outcomes).containsEntry("corr-2", "NEUTRAL");
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
        return new GameState(400, 200, 62, 44, units, List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), 350);
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
        return new GameState(400, 200, 62, 44, List.of(), buildings,
            List.of(), List.of(), List.of(), List.of(), List.of(), 350);
    }

    static class TestTrustRecorder extends CoachingEffectivenessTrustRecorder {
        String lastOutcome;
        Map<String, String> outcomes = new LinkedHashMap<>();
        @Override
        public void record(String correlationId, String outcome, CoachingAdvice advice) {
            this.lastOutcome = outcome;
            this.outcomes.put(correlationId, outcome);
        }
    }
}
