package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CascadingPatternClassifierTest {

    @Test
    void droolsAboveThreshold_resolvesWithDroolsSource() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "8+ lings"));
        var result = classifier.classify(evidence, List.of(), null, 100, -1, null);

        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
        assertTrue(result.assessments().get(0).confidence() >= 0.7);
        assertFalse(result.llmTriggered());
    }

    @Test
    void belowDispatchThreshold_noAssessment() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.2, "few lings"));
        var result = classifier.classify(evidence, List.of(), null, 100, -1, null);
        assertTrue(result.assessments().isEmpty());
    }

    @Test
    void droolsBelowCascadeThreshold_stillPublishesAboveDispatch() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "some lings"));
        var result = classifier.classify(evidence, List.of(), null, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
    }

    @Test
    void droolsExactlyAtThreshold_resolves() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.7, "lings"));
        var result = classifier.classify(evidence, List.of(), null, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
    }

    @Test
    void decayReducesCumulativeOverTime() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        classifier.classify(List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "lings")),
            List.of(), null, 100, -1, null);
        var result = classifier.classify(List.of(), List.of(), null, 1100, 100, null);
        assertTrue(result.assessments().isEmpty() || result.assessments().get(0).confidence() < 0.8);
    }

    @Test
    void revisionDampensConfidence() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        classifier.classify(List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "lings")),
            List.of(), null, 100, -1, null);
        var revisions = List.of(new ConfidenceRevision(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.99, "expansion detected"));
        var result = classifier.classify(List.of(), revisions, null, 200, 100, null);
        assertTrue(result.assessments().isEmpty() || result.assessments().get(0).confidence() < 0.8);
    }

    @Test
    void multipleArchetypes_highestConfidenceFirst() {
        var classifier = new CascadingPatternClassifier(0.3, 0.5);
        var evidence = List.of(
            new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "lings"),
            new EvidenceMarker(StrategyArchetype.ZERG_ROACH_RUSH, 0.8, "roaches"));
        var result = classifier.classify(evidence, List.of(), null, 100, -1, null);
        assertEquals(2, result.assessments().size());
        assertTrue(result.assessments().get(0).confidence() >= result.assessments().get(1).confidence());
    }

    @Test
    void resetClearsCumulativeState() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        classifier.classify(List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "lings")),
            List.of(), null, 100, -1, null);
        classifier.reset();
        var result = classifier.classify(List.of(), List.of(), null, 200, 100, null);
        assertTrue(result.assessments().isEmpty());
    }
}
