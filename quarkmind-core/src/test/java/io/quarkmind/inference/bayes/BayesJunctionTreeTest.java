package io.quarkmind.inference.bayes;

import io.quarkmind.inference.bayes.graph.GraphNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BayesJunctionTreeTest {

    @Test
    void sprinklerNetworkProducesCalibratedPosteriors() {
        var graph = new BayesNetwork();

        GraphNode<BayesVariable> cloudyNode = graph.addNode();
        cloudyNode.setContent(new BayesVariable<>("Cloudy",
            cloudyNode.getId(), new String[]{"true", "false"},
            new double[][]{{0.5, 0.5}}));

        GraphNode<BayesVariable> sprinklerNode = graph.addNode();
        sprinklerNode.setContent(new BayesVariable<>("Sprinkler",
            sprinklerNode.getId(), new String[]{"true", "false"},
            new double[][]{{0.1, 0.9}, {0.5, 0.5}}));

        GraphNode<BayesVariable> rainNode = graph.addNode();
        rainNode.setContent(new BayesVariable<>("Rain",
            rainNode.getId(), new String[]{"true", "false"},
            new double[][]{{0.8, 0.2}, {0.2, 0.8}}));

        GraphNode<BayesVariable> wetNode = graph.addNode();
        wetNode.setContent(new BayesVariable<>("WetGrass",
            wetNode.getId(), new String[]{"true", "false"},
            new double[][]{{0.99, 0.01}, {0.90, 0.10}, {0.90, 0.10}, {0.0, 1.0}}));

        graph.addEdge(cloudyNode, sprinklerNode);
        graph.addEdge(cloudyNode, rainNode);
        graph.addEdge(sprinklerNode, wetNode);
        graph.addEdge(rainNode, wetNode);

        JunctionTree jTree = new JunctionTreeBuilder(graph).build();
        BayesInstance<?> instance = new BayesInstance<>(jTree);

        instance.setLikelyhood("WetGrass", new double[]{1.0, 0.0});
        instance.globalUpdate();

        BayesVariableState rainState = instance.marginalize("Rain");
        assertTrue(rainState.getDistribution()[0] > 0.5,
            "Rain should be likely given wet grass");

        double sum = 0;
        for (double p : rainState.getDistribution()) sum += p;
        assertEquals(1.0, sum, 0.001, "Posteriors must sum to 1.0");
    }

    @Test
    void naiveBayesStarWithSoftEvidence() {
        var graph = new BayesNetwork();

        GraphNode<BayesVariable> strategyNode = graph.addNode();
        strategyNode.setContent(new BayesVariable<>("Strategy",
            strategyNode.getId(), new String[]{"RUSH", "MACRO"},
            new double[][]{{0.3, 0.7}}));

        GraphNode<BayesVariable> rushDetNode = graph.addNode();
        rushDetNode.setContent(new BayesVariable<>("RushDet",
            rushDetNode.getId(), new String[]{"HIGH", "LOW"},
            new double[][]{{0.85, 0.15}, {0.10, 0.90}}));

        GraphNode<BayesVariable> ecoDetNode = graph.addNode();
        ecoDetNode.setContent(new BayesVariable<>("EcoDet",
            ecoDetNode.getId(), new String[]{"HIGH", "LOW"},
            new double[][]{{0.10, 0.90}, {0.90, 0.10}}));

        graph.addEdge(strategyNode, rushDetNode);
        graph.addEdge(strategyNode, ecoDetNode);

        JunctionTree jTree = new JunctionTreeBuilder(graph).build();
        BayesInstance<?> instance = new BayesInstance<>(jTree);

        instance.setLikelyhood("RushDet", new double[]{0.8, 0.2});
        instance.setLikelyhood("EcoDet", new double[]{0.2, 0.8});
        instance.globalUpdate();

        BayesVariableState result = instance.marginalize("Strategy");
        assertTrue(result.getDistribution()[0] > result.getDistribution()[1],
            "RUSH should be more likely given rush-high + eco-low");

        double sum = 0;
        for (double p : result.getDistribution()) sum += p;
        assertEquals(1.0, sum, 0.001, "Posteriors must sum to 1.0");
    }

    @Test
    void resetRestoresToPrior() {
        var graph = new BayesNetwork();

        GraphNode<BayesVariable> strategyNode = graph.addNode();
        strategyNode.setContent(new BayesVariable<>("Strategy",
            strategyNode.getId(), new String[]{"A", "B"},
            new double[][]{{0.3, 0.7}}));

        GraphNode<BayesVariable> detNode = graph.addNode();
        detNode.setContent(new BayesVariable<>("Det",
            detNode.getId(), new String[]{"HIGH", "LOW"},
            new double[][]{{0.9, 0.1}, {0.1, 0.9}}));

        graph.addEdge(strategyNode, detNode);

        JunctionTree jTree = new JunctionTreeBuilder(graph).build();
        BayesInstance<?> instance = new BayesInstance<>(jTree);

        instance.setLikelyhood("Det", new double[]{0.9, 0.1});
        instance.globalUpdate();
        BayesVariableState before = instance.marginalize("Strategy");
        assertTrue(before.getDistribution()[0] > 0.5, "A should be dominant with evidence");

        instance.unsetLikelyhood(instance.getVariables().get("Det"));
        instance.globalUpdate();
        BayesVariableState after = instance.marginalize("Strategy");
        assertEquals(0.3, after.getDistribution()[0], 0.05, "Should return close to prior after unset");
    }
}
