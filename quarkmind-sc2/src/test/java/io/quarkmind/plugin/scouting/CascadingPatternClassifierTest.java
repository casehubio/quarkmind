package io.quarkmind.plugin.scouting;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.TensorClassifier;
import io.quarkmind.agency.context.MutableMapCaseContext;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void onnxResolvesWhenDroolsBelowThreshold() {
        var model = InMemoryInferenceModel.returning(0.9f, 0.05f, 0.05f);
        var onnx = new TensorClassifier(model, List.of(
                StrategyArchetype.ZERG_ZERGLING_RUSH.name(),
                StrategyArchetype.ZERG_MACRO.name(),
                StrategyArchetype.TERRAN_MARINE_RUSH.name()));

        var classifier = new CascadingPatternClassifier(0.7, 0.5, onnx);
        var evidence   = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.4, "some lings"));
        var features   = Map.of("input", new float[][]{{1.0f, 2.0f, 3.0f}});

        var result = classifier.classify(evidence, List.of(), features, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.ONNX, result.assessments().get(0).source());
        assertFalse(result.llmTriggered());
    }

    @Test
    void onnxBelowThreshold_fallsThroughToLlm() {
        var model = InMemoryInferenceModel.returning(0.3f, 0.4f, 0.3f);
        var onnx = new TensorClassifier(model, List.of(
                StrategyArchetype.ZERG_ZERGLING_RUSH.name(),
                StrategyArchetype.ZERG_MACRO.name(),
                StrategyArchetype.TERRAN_MARINE_RUSH.name()));

        var classifier = new CascadingPatternClassifier(0.7, 0.5, onnx);
        classifier.setLlmFallbackConfig(true, 0.5, 100, 50);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.4, "some lings"));
        var features = Map.of("input", new float[][]{{1.0f, 2.0f, 3.0f}});
        var ctx      = new MutableMapCaseContext(Map.of());

        var result = classifier.classify(evidence, List.of(), features, 200, -1, ctx);
        assertTrue(result.llmTriggered());
    }

    @Test
    void onnxUnavailable_fallsThroughToLlm() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5, (TensorClassifier) null);
        classifier.setLlmFallbackConfig(true, 0.5, 100, 50);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.4, "some lings"));
        var ctx      = new MutableMapCaseContext(Map.of());

        var result = classifier.classify(evidence, List.of(), null, 200, -1, ctx);
        assertTrue(result.llmTriggered());
        assertTrue(result.assessments().isEmpty() || result.assessments().get(0).source() != AssessmentSource.ONNX);
    }

    @Test
    void droolsAboveThreshold_onnxNotCalled() {
        var model = InMemoryInferenceModel.returning(0.9f, 0.05f, 0.05f);
        var onnx = new TensorClassifier(model, List.of(
                StrategyArchetype.ZERG_ZERGLING_RUSH.name(),
                StrategyArchetype.ZERG_MACRO.name(),
                StrategyArchetype.TERRAN_MARINE_RUSH.name()));

        var classifier = new CascadingPatternClassifier(0.7, 0.5, onnx);
        var evidence   = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "many lings"));
        var features   = Map.of("input", new float[][]{{1.0f, 2.0f, 3.0f}});

        var result = classifier.classify(evidence, List.of(), features, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
    }


}
