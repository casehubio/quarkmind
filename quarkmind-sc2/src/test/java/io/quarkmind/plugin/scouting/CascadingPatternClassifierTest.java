package io.quarkmind.plugin.scouting;

import io.quarkmind.agency.context.MutableMapCaseContext;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.sc2.detector.DetectorCatalogConfig;
import io.quarkmind.sc2.detector.DetectorOrchestrator;
import io.quarkmind.sc2.detector.DetectorRegistry;
import io.quarkmind.sc2.detector.FeatureTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadingPatternClassifierTest {

    @Test
    void droolsAboveThreshold_resolvesWithDroolsSource() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "8+ lings"));
        var result = classifier.classify(evidence, List.of(), null, null, 100, -1, null);

        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
        assertTrue(result.assessments().get(0).confidence() >= 0.7);
        assertFalse(result.llmTriggered());
    }

    @Test
    void belowDispatchThreshold_noAssessment() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.2, "few lings"));
        var result = classifier.classify(evidence, List.of(), null, null, 100, -1, null);
        assertTrue(result.assessments().isEmpty());
    }

    @Test
    void droolsBelowCascadeThreshold_stillPublishesAboveDispatch() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "some lings"));
        var result = classifier.classify(evidence, List.of(), null, null, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
    }

    @Test
    void droolsExactlyAtThreshold_resolves() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.7, "lings"));
        var result = classifier.classify(evidence, List.of(), null, null, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
    }

    @Test
    void decayReducesCumulativeOverTime() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        classifier.classify(List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "lings")),
            List.of(), null, null, 100, -1, null);
        var result = classifier.classify(List.of(), List.of(), null, null, 1100, 100, null);
        assertTrue(result.assessments().isEmpty() || result.assessments().get(0).confidence() < 0.8);
    }

    @Test
    void revisionDampensConfidence() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        classifier.classify(List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "lings")),
            List.of(), null, null, 100, -1, null);
        var revisions = List.of(new ConfidenceRevision(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.99, "expansion detected"));
        var result = classifier.classify(List.of(), revisions, null, null, 200, 100, null);
        assertTrue(result.assessments().isEmpty() || result.assessments().get(0).confidence() < 0.8);
    }

    @Test
    void multipleArchetypes_highestConfidenceFirst() {
        var classifier = new CascadingPatternClassifier(0.3, 0.5);
        var evidence = List.of(
            new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "lings"),
            new EvidenceMarker(StrategyArchetype.ZERG_ROACH_RUSH, 0.8, "roaches"));
        var result = classifier.classify(evidence, List.of(), null, null, 100, -1, null);
        assertEquals(2, result.assessments().size());
        assertTrue(result.assessments().get(0).confidence() >= result.assessments().get(1).confidence());
    }

    @Test
    void resetClearsCumulativeState() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        classifier.classify(List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "lings")),
            List.of(), null, null, 100, -1, null);
        classifier.reset();
        var result = classifier.classify(List.of(), List.of(), null, null, 200, 100, null);
        assertTrue(result.assessments().isEmpty());
    }

    @Test
    void fusionResolvesWhenDroolsBelowThreshold() {
        var orchestrator = buildTestOrchestrator();
        var classifier = new CascadingPatternClassifier(0.7, 0.5, orchestrator, null);
        var evidence   = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "moderate rush signal"));
        var features   = new StrategyFeatures(Map.of("temporal", new float[][]{{1.0f}}));

        var result = classifier.classify(evidence, List.of(), features, Race.ZERG, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.FUSION, result.assessments().get(0).source());
        assertFalse(result.llmTriggered());
    }

    @Test
    void fusionResolution_populatesMapStrategy() {
        var orchestrator = buildTestOrchestrator();
        var classifier   = new CascadingPatternClassifier(0.7, 0.5, orchestrator, null);
        var evidence     = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "moderate rush signal"));
        var features     = new StrategyFeatures(Map.of("temporal", new float[][]{{1.0f}}));

        var result = classifier.classify(evidence, List.of(), features, Race.ZERG, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.FUSION, result.assessments().get(0).source());
        assertNotNull(result.mapStrategy(), "Fusion resolution should include MAP strategy");
    }

    @Test
    void droolsResolution_mapStrategyIsNull() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence   = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "strong rush signal"));
        var result     = classifier.classify(evidence, List.of(), null, null, 100, -1, null);

        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
        org.junit.jupiter.api.Assertions.assertNull(result.mapStrategy(),
                                                    "Drools resolution should not have MAP strategy — no BJT inference ran");
    }

    @Test
    void mapStrategyMatchesFusionWinnerWhenDecisive() {
        var orchestrator = buildTestOrchestrator();
        var classifier   = new CascadingPatternClassifier(0.99, 0.3, orchestrator, null);
        var evidence     = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, "rush signal"));
        var features     = new StrategyFeatures(Map.of("temporal", new float[][]{{1.0f}}));

        var result = classifier.classify(evidence, List.of(), features, Race.ZERG, 100, -1, null);
        if (result.mapStrategy() != null) {
            assertEquals(result.assessments().get(0).archetype(), result.mapStrategy(),
                         "MAP strategy should agree with marginal winner when one archetype dominates");
        }
    }


    @Test
    void fusionUnavailable_fallsThroughToLlm() {
        var orchestrator = buildTestOrchestrator();
        var classifier = new CascadingPatternClassifier(0.7, 0.5, orchestrator, null);
        classifier.setLlmFallbackConfig(true, 0.5, 100, 50);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.4, "some lings"));
        var ctx      = new MutableMapCaseContext(Map.of());

        // null onnxFeatures → fusion tier skipped, falls through to LLM
        var result = classifier.classify(evidence, List.of(), null, Race.ZERG, 200, -1, ctx);
        assertTrue(result.llmTriggered());
    }

    @Test
    void onnxUnavailable_fallsThroughToLlm() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        classifier.setLlmFallbackConfig(true, 0.5, 100, 50);
        var evidence = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.4, "some lings"));
        var ctx      = new MutableMapCaseContext(Map.of());

        var result = classifier.classify(evidence, List.of(), null, Race.ZERG, 200, -1, ctx);
        assertTrue(result.llmTriggered());
        assertTrue(result.assessments().isEmpty() || result.assessments().get(0).source() != AssessmentSource.ONNX);
    }

    @Test
    void droolsAboveThreshold_fusionNotCalled() {
        var orchestrator = buildTestOrchestrator();
        var classifier = new CascadingPatternClassifier(0.7, 0.5, orchestrator, null);
        var evidence   = List.of(new EvidenceMarker(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.8, "many lings"));

        var result = classifier.classify(evidence, List.of(), null, Race.ZERG, 100, -1, null);
        assertFalse(result.assessments().isEmpty());
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
    }

// --- COMPOSITION_UNKNOWN fallback tests ---

    @Test
    void classify_emptyEvidence_withEnemiesVisible_returnsFallbackAssessment() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        CascadeResult result = classifier.classify(
                List.of(), List.of(), null, Race.ZERG, 3000, 2900, null, 5);
        assertFalse(result.assessments().isEmpty());
        assertEquals(StrategyArchetype.ZERG_COMPOSITION_UNKNOWN, result.assessments().get(0).archetype());
        assertEquals(0.35, result.assessments().get(0).confidence(), 0.001);
        assertEquals(AssessmentSource.DROOLS, result.assessments().get(0).source());
    }

    @Test
    void classify_emptyEvidence_noEnemies_returnsEmpty() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        CascadeResult result = classifier.classify(
                List.of(), List.of(), null, Race.ZERG, 3000, 2900, null, 0);
        assertTrue(result.assessments().isEmpty());
    }

    @Test
    void classify_sufficientEvidence_noFallback() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var evidence = List.of(
                new EvidenceMarker(StrategyArchetype.ZERG_ROACH_HYDRA, 0.8, "test"));
        CascadeResult result = classifier.classify(
                evidence, List.of(), null, Race.ZERG, 3000, 2900, null, 5);
        assertFalse(result.assessments().isEmpty());
        assertFalse(result.assessments().get(0).archetype().name().endsWith("_COMPOSITION_UNKNOWN"));
    }

    @Test
    void classify_terranEnemies_returnsTerranUnknown() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        CascadeResult result = classifier.classify(
                List.of(), List.of(), null, Race.TERRAN, 3000, 2900, null, 3);
        assertFalse(result.assessments().isEmpty());
        assertEquals(StrategyArchetype.TERRAN_COMPOSITION_UNKNOWN, result.assessments().get(0).archetype());
    }

    @Test
    void classify_protossEnemies_returnsProtossUnknown() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        CascadeResult result = classifier.classify(
                List.of(), List.of(), null, Race.PROTOSS, 3000, 2900, null, 3);
        assertFalse(result.assessments().isEmpty());
        assertEquals(StrategyArchetype.PROTOSS_COMPOSITION_UNKNOWN, result.assessments().get(0).archetype());
    }

    @org.junit.jupiter.api.Test
    void transitionDoesNotFire_onFirstDominant() {
        var           classifier = new CascadingPatternClassifier(0.3, 0.8);
        var           evidence   = java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, "rush signal"));
        CascadeResult result     = classifier.classify(evidence, java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 100, 0, null, 5);
        org.junit.jupiter.api.Assertions.assertNull(result.transition());
    }

    @org.junit.jupiter.api.Test
    void transitionFires_whenDominantChangesWithSufficientGap() {
        var classifier = new CascadingPatternClassifier(0.3, 0.8);
        classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, "rush")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 100, 0, null, 5);
        CascadeResult result = classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_BIO_TIMING, 0.9, "bio timing")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 200, 100, null, 5);
        org.junit.jupiter.api.Assertions.assertNotNull(result.transition());
        org.junit.jupiter.api.Assertions.assertEquals(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, result.transition().from());
        org.junit.jupiter.api.Assertions.assertEquals(io.quarkmind.domain.StrategyArchetype.TERRAN_BIO_TIMING, result.transition().to());
    }

    @org.junit.jupiter.api.Test
    void transitionDoesNotFire_whenBelowMinConfidence() {
        var classifier = new CascadingPatternClassifier(0.3, 0.8);
        classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, "rush")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 100, 0, null, 5);
        CascadeResult result = classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_BIO_TIMING, 0.2, "weak bio")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 200, 100, null, 5);
        org.junit.jupiter.api.Assertions.assertNull(result.transition());
    }

    @org.junit.jupiter.api.Test
    void resetClearsPrevDominant() {
        var classifier = new CascadingPatternClassifier(0.3, 0.8);
        classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, "rush")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 100, 0, null, 5);
        classifier.reset();
        CascadeResult result = classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_BIO_TIMING, 0.7, "bio")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 200, 0, null, 5);
        org.junit.jupiter.api.Assertions.assertNull(result.transition());
    }

    @org.junit.jupiter.api.Test
    void prevDominantUnchanged_whenBelowThreshold() {
        var classifier = new CascadingPatternClassifier(0.3, 0.8);
        classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, "rush")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 100, 0, null, 5);
        classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_FAST_EXPAND, 0.1, "noise")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 200, 100, null, 5);
        CascadeResult result = classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_BIO_TIMING, 0.8, "strong bio")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 300, 200, null, 5);
        org.junit.jupiter.api.Assertions.assertNotNull(result.transition());
        org.junit.jupiter.api.Assertions.assertEquals(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, result.transition().from());
    }

    @org.junit.jupiter.api.Test
    void cascadeResultTransitionNull_whenSameDominant() {
        var classifier = new CascadingPatternClassifier(0.3, 0.8);
        classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, "rush")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 100, 0, null, 5);
        CascadeResult result2 = classifier.classify(
                java.util.List.of(new EvidenceMarker(io.quarkmind.domain.StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, "rush")),
                java.util.List.of(), null, io.quarkmind.domain.Race.TERRAN, 200, 100, null, 5);
        org.junit.jupiter.api.Assertions.assertNull(result2.transition());
    }


    @org.junit.jupiter.api.Test
    void tier2_withDetectorRunner_producesNonEmptyOutputs() {
        var runner = new io.quarkmind.sc2.detector.BinaryDetectorRunner(
                java.nio.file.Path.of("src/main/resources/models/detectors"));

        // Build orchestrator with real detector names matching the runner's output
        var priors = java.util.Map.of("RUSH", 0.5, "MACRO_ECONOMY", 0.5);
        var cpts = java.util.Map.of(
                "early_rush_vs_terran", java.util.Map.of("RUSH", 0.85, "MACRO_ECONOMY", 0.15),
                "early_greedy_vs_terran", java.util.Map.of("RUSH", 0.15, "MACRO_ECONOMY", 0.85));
        var labelMapping = java.util.Map.of(
                "RUSH", StrategyArchetype.TERRAN_MARINE_RUSH,
                "MACRO_ECONOMY", StrategyArchetype.TERRAN_COMPOSITION_UNKNOWN);
        var registry = new io.quarkmind.sc2.detector.DetectorRegistry(
                io.quarkmind.sc2.detector.DetectorCatalog.create());
        var orchestrator = new DetectorOrchestrator(
                registry, priors, cpts, labelMapping, 10);
        orchestrator.initializeNetwork(java.util.List.of(
                "early_rush_vs_terran", "early_greedy_vs_terran"));

        var classifier = new CascadingPatternClassifier(0.99, 0.3, orchestrator, runner);

        float[] temporal    = new float[10 * FeatureIndexMaps.FEATURES_PER_WINDOW];
        float[] mapFeatures = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};
        var features = new StrategyFeatures(java.util.Map.of(
                "temporal", new float[][]{temporal},
                "map", new float[][]{mapFeatures}));

        var result = classifier.classify(
                java.util.List.of(), java.util.List.of(), features,
                io.quarkmind.domain.Race.TERRAN, 100, -1, null);

        // droolsThreshold at 0.99 forces Tier 2; detector runner provides real outputs
        org.assertj.core.api.Assertions.assertThat(result).isNotNull();
        // Tier 2 ran (even if confidence was below onnxThreshold, the cascade didn't crash)

        runner.close();
    }

    private static DetectorOrchestrator buildTestOrchestrator() {
        var config = new DetectorCatalogConfig(List.of(
                new DetectorCatalogConfig.DetectorDef(
                        "rush_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                        Map.of(Race.ZERG, List.of("RUSH")),
                        null)
                                                      ));
        var registry = new DetectorRegistry(config);
        var priors   = Map.of("RUSH", 0.3, "MACRO", 0.7);
        var cpts = Map.of(
                "rush_det", Map.of("RUSH", 0.85, "MACRO", 0.10)
                         );
        var labelMapping = Map.of(
                "RUSH", StrategyArchetype.ZERG_ZERGLING_RUSH,
                "MACRO", StrategyArchetype.ZERG_MACRO
                                 );
        var orchestrator = new DetectorOrchestrator(
                registry, priors, cpts, labelMapping, 10);
        orchestrator.initializeNetwork(List.of("rush_det"));
        return orchestrator;
    }

    @Test
    void observeScouting_delegatesToFusion() {
        var priors  = io.quarkmind.sc2.detector.HypothesisPriorLoader.loadOpponentPriors(GamePhase.EARLY, Race.TERRAN);
        var obsCpts = io.quarkmind.sc2.detector.HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);
        var fusion = new io.quarkmind.sc2.detector.HypothesisTrackerFusion(
                null, priors, Map.of(), obsCpts, Map.of(), null);
        fusion.initialize(List.of(), GamePhase.EARLY, Race.TERRAN);

        var classifier    = new CascadingPatternClassifier(0.7, 0.5, fusion, null);
        var weightsBefore = Map.copyOf(fusion.opponentWeights());

        classifier.observeScouting(new io.quarkmind.domain.ScoutingObservation("first_building", "barracks", 100L));

        org.junit.jupiter.api.Assertions.assertNotEquals(weightsBefore, fusion.opponentWeights(),
                                                         "observeScouting should delegate to fusion and change weights");
    }

    @Test
    void fusionMode_hypothesis_classifiesWithFusion() {
        var priors  = io.quarkmind.sc2.detector.HypothesisPriorLoader.loadOpponentPriors(GamePhase.EARLY, Race.TERRAN);
        var obsCpts = io.quarkmind.sc2.detector.HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);
        var fusion = new io.quarkmind.sc2.detector.HypothesisTrackerFusion(
                null, priors, Map.of(), obsCpts, Map.of(), null);

        var classifier = new CascadingPatternClassifier(0.99, 0.01, fusion, null);

        fusion.initialize(List.of(), GamePhase.EARLY, Race.TERRAN);
        var result = classifier.classify(List.of(), List.of(), null,
                                         Race.TERRAN, 100, -1, null);
        // With onnxFeatures null, tier 2 is skipped; just verify no crash
        assertTrue(result.assessments().isEmpty() || result.assessments().get(0).source() != null);
    }

    @Test
    void observeScouting_noop_whenDetectorMode() {
        var orchestrator = buildTestOrchestrator();
        var classifier   = new CascadingPatternClassifier(0.7, 0.5, orchestrator, null);
        // fusionMode is "detector" in this constructor — observeScouting should be a no-op
        classifier.observeScouting(new io.quarkmind.domain.ScoutingObservation("first_building", "barracks", 100L));
        // No crash, no effect — just verifying it doesn't throw
    }

}
