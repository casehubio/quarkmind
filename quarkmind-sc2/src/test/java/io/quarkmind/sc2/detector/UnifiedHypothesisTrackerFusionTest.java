package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.agent.TimeBasedPhaseResolver;
import io.quarkmind.plugin.scouting.EvidenceMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedHypothesisTrackerFusionTest {

    private HypothesisTrackerFusion fusion;
    private StrategyTaxonomy taxonomy;
    private DetectorRegistry registry;
    private List<String> detectorNames;

    @BeforeEach
    void setUp() {
        taxonomy = new StrategyTaxonomy();
        taxonomy.init();

        var config = new DetectorCatalogConfig(List.of(
            new DetectorCatalogConfig.DetectorDef(
                "rush_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                Map.of(Race.PROTOSS, List.of("RUSH")),
                null)
        ));
        registry = new DetectorRegistry(config);
        detectorNames = List.of("rush_det");

        Map<String, Double> priors = HypothesisPriorLoader.loadUnifiedPriors(Race.PROTOSS);
        Map<String, Map<String, Double>> detectorCpts = Map.of(
            "rush_det", buildUniformCpts(priors.keySet(), 0.5)
        );

        fusion = new HypothesisTrackerFusion(
            registry, priors, detectorCpts,
            new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of()),
            Map.of(), new TimeBasedPhaseResolver()
        );
    }

    @Test
    void initializeUnifiedIncludesAllPhaseArchetypes() {
        fusion.initializeUnified(detectorNames, Race.PROTOSS, taxonomy);

        var weights = fusion.opponentWeights();
        boolean hasEarly = weights.keySet().stream()
            .anyMatch(k -> StrategyArchetype.valueOf(k).phase() == GamePhase.EARLY);
        boolean hasMid = weights.keySet().stream()
            .anyMatch(k -> StrategyArchetype.valueOf(k).phase() == GamePhase.MID);
        boolean hasLate = weights.keySet().stream()
            .anyMatch(k -> StrategyArchetype.valueOf(k).phase() == GamePhase.LATE);
        assertTrue(hasEarly && hasMid && hasLate,
            "Unified tracker should include archetypes from all phases");
    }

    @Test
    void classifyDoesNotRebuildAcrossPhases() {
        fusion.initializeUnified(detectorNames, Race.PROTOSS, taxonomy);

        var result1 = fusion.classifyUnified(Map.of("rush_det", 0.9f), List.of(), 100, 2.0);
        var keysAtEarly = fusion.opponentWeights().keySet();

        var result2 = fusion.classifyUnified(Map.of("rush_det", 0.5f), List.of(), 200, 7.0);
        var keysAtMid = fusion.opponentWeights().keySet();

        assertEquals(keysAtEarly, keysAtMid, "Archetype set should not change across phases");
    }

    @Test
    void phaseWindowReducesEarlyArchetypeAtMidTime() {
        fusion.initializeUnified(detectorNames, Race.PROTOSS, taxonomy);

        fusion.classifyUnified(Map.of(), List.of(), 100, 8.0);
        var weights = fusion.opponentWeights();

        for (var entry : weights.entrySet()) {
            var arch = StrategyArchetype.valueOf(entry.getKey());
            if (arch.phase() == GamePhase.EARLY && arch.race() == Race.PROTOSS) {
                assertTrue(entry.getValue() < 0.10,
                    arch + " (EARLY) should have low weight at minute 8, got " + entry.getValue());
            }
        }
    }

    @Test
    void evidenceOvercomesLowPhaseWeight() {
        fusion.initializeUnified(detectorNames, Race.PROTOSS, taxonomy);

        Map<String, Map<String, Double>> rushCpts = Map.of(
            "rush_det", Map.of("PROTOSS_GATEWAY_RUSH", 0.95)
        );
        var rushFusion = new HypothesisTrackerFusion(
            registry,
            HypothesisPriorLoader.loadUnifiedPriors(Race.PROTOSS),
            rushCpts,
            new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of()),
            Map.of(), new TimeBasedPhaseResolver()
        );
        rushFusion.initializeUnified(detectorNames, Race.PROTOSS, taxonomy);

        rushFusion.classifyUnified(Map.of("rush_det", 0.95f), List.of(), 100, 8.0);
        var weights = rushFusion.opponentWeights();

        double rushWeight = weights.getOrDefault("PROTOSS_GATEWAY_RUSH", 0.0);
        assertTrue(rushWeight > 0.01,
            "Strong evidence should overcome low phase weight, got " + rushWeight);
    }

    private Map<String, Double> buildUniformCpts(java.util.Set<String> archetypes, double value) {
        Map<String, Double> cpts = new LinkedHashMap<>();
        for (String arch : archetypes) {
            cpts.put(arch, value);
        }
        return cpts;
    }
}
