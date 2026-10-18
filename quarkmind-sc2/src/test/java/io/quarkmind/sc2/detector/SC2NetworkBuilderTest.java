package io.quarkmind.sc2.detector;

import io.quarkmind.inference.bayes.BayesInstance;
import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesVariableState;
import io.quarkmind.inference.bayes.JunctionTree;
import io.quarkmind.inference.bayes.JunctionTreeBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SC2NetworkBuilderTest {

    @Test
    void buildProducesNetworkWithCorrectNodeCount() {
        var cpts = Map.of(
            "early_rush", Map.of("RUSH", 0.85, "MACRO", 0.10, "TECH", 0.30),
            "early_eco", Map.of("RUSH", 0.08, "MACRO", 0.90, "TECH", 0.25)
        );
        var priors = Map.of("RUSH", 0.25, "MACRO", 0.45, "TECH", 0.30);

        BayesNetwork network = SC2NetworkBuilder.build(
            priors, List.of("early_rush", "early_eco"), cpts);

        assertNotNull(network);
        assertEquals(3, network.size());
    }

    @Test
    void builtNetworkProducesValidPosteriors() {
        var cpts = Map.of(
            "rush_det", Map.of("RUSH", 0.85, "MACRO", 0.10),
            "eco_det", Map.of("RUSH", 0.10, "MACRO", 0.90)
        );
        var priors = Map.of("RUSH", 0.3, "MACRO", 0.7);

        BayesNetwork network = SC2NetworkBuilder.build(
            priors, List.of("rush_det", "eco_det"), cpts);

        JunctionTree jTree = new JunctionTreeBuilder(network).build();
        BayesInstance<?> instance = new BayesInstance<>(jTree);

        instance.setLikelyhood("rush_det", new double[]{0.9, 0.1});
        instance.setLikelyhood("eco_det", new double[]{0.1, 0.9});
        instance.globalUpdate();

        BayesVariableState result = instance.marginalize("Strategy");
        // sortedArchetypes: MACRO=0, RUSH=1
        assertTrue(result.getDistribution()[1] > result.getDistribution()[0],
            "RUSH (idx 1) should dominate given rush-high + eco-low");

        double sum = 0;
        for (double p : result.getDistribution()) sum += p;
        assertEquals(1.0, sum, 0.001);
    }

    @Test
    void cptValuesAreClampedToAvoidZero() {
        var cpts = Map.of(
            "det", Map.of("A", 0.0, "B", 1.0)
        );
        var priors = Map.of("A", 0.5, "B", 0.5);

        BayesNetwork network = SC2NetworkBuilder.build(
            priors, List.of("det"), cpts);

        JunctionTree jTree = new JunctionTreeBuilder(network).build();
        BayesInstance<?> instance = new BayesInstance<>(jTree);

        instance.setLikelyhood("det", new double[]{1.0, 0.0});
        instance.globalUpdate();

        BayesVariableState result = instance.marginalize("Strategy");
        assertFalse(Double.isNaN(result.getDistribution()[0]),
            "Clamped CPTs should prevent NaN");
    }

    @Test
    void archetypeOrderIsConsistent() {
        var priors = Map.of("RUSH", 0.3, "MACRO", 0.5, "TECH", 0.2);
        var cpts = Map.of(
            "det", Map.of("RUSH", 0.9, "MACRO", 0.1, "TECH", 0.5)
        );

        String[] archetypes = SC2NetworkBuilder.sortedArchetypes(priors);
        assertArrayEquals(new String[]{"MACRO", "RUSH", "TECH"}, archetypes);
    }
}
