package io.quarkmind.perception;

import io.quarkmind.inference.bayes.BayesInstance;
import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesVariable;
import io.quarkmind.inference.bayes.BayesVariableState;
import io.quarkmind.inference.bayes.JunctionTree;
import io.quarkmind.inference.bayes.JunctionTreeBuilder;
import io.quarkmind.inference.bayes.graph.GraphNode;

public class EvidenceFusionEngine {

    private JunctionTree network;
    private BayesInstance<?> instance;
    private String targetVariable;
    private String[] outcomeNames;

    public void initialize(BayesNetwork graph, String targetVariable) {
        this.targetVariable = targetVariable;
        this.network = new JunctionTreeBuilder(graph).build();
        this.instance = new BayesInstance<>(network);

        for (GraphNode<BayesVariable> node : graph) {
            if (node.getContent().getName().equals(targetVariable)) {
                this.outcomeNames = (String[]) node.getContent().getOutcomes();
                break;
            }
        }
    }

    public void setEvidence(String variable, double[] likelihood) {
        instance.setLikelyhood(variable, likelihood);
    }

    public void update() {
        instance.globalUpdate();
    }

    public double[] marginalize() {
        BayesVariableState state = instance.marginalize(targetVariable);
        return state.getDistribution();
    }

    public String[] getOutcomeNames() {
        return outcomeNames.clone();
    }

    public void reset() {
        this.instance = new BayesInstance<>(network);
    }
}
