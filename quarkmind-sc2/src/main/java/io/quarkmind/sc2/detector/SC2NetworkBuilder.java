package io.quarkmind.sc2.detector;

import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesVariable;
import io.quarkmind.inference.bayes.graph.GraphNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SC2NetworkBuilder {

    public static BayesNetwork build(
            Map<String, Double> priors,
            List<String> detectorNames,
            Map<String, Map<String, Double>> cpts) {

        var graph = new BayesNetwork();
        String[] archetypes = sortedArchetypes(priors);

        GraphNode<BayesVariable> stratNode = graph.addNode();
        double[] priorArray = new double[archetypes.length];
        for (int i = 0; i < archetypes.length; i++) {
            priorArray[i] = priors.getOrDefault(archetypes[i], 1.0 / archetypes.length);
        }
        stratNode.setContent(new BayesVariable<>("Strategy",
            stratNode.getId(), archetypes, new double[][]{priorArray}));

        for (String detName : detectorNames) {
            GraphNode<BayesVariable> detNode = graph.addNode();
            Map<String, Double> cpt = cpts.get(detName);

            double[][] cptArray = new double[archetypes.length][2];
            for (int i = 0; i < archetypes.length; i++) {
                double pHigh = cpt.getOrDefault(archetypes[i], 0.5);
                pHigh = Math.max(0.01, Math.min(0.99, pHigh));
                cptArray[i][0] = pHigh;
                cptArray[i][1] = 1.0 - pHigh;
            }

            detNode.setContent(new BayesVariable<>(detName,
                detNode.getId(), new String[]{"HIGH", "LOW"}, cptArray));
            graph.addEdge(stratNode, detNode);
        }

        return graph;
    }

    static String[] sortedArchetypes(Map<String, Double> priors) {
        return priors.keySet().stream().sorted().toArray(String[]::new);
    }
}
