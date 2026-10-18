package io.quarkmind.perception;

import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesVariable;
import io.quarkmind.inference.bayes.graph.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceFusionEngineTest {

    private EvidenceFusionEngine engine;

    @BeforeEach
    void setUp() {
        var graph = new BayesNetwork();

        GraphNode<BayesVariable> stratNode = graph.addNode();
        stratNode.setContent(new BayesVariable<>("Strategy",
            stratNode.getId(), new String[]{"RUSH", "MACRO", "TECH"},
            new double[][]{{0.25, 0.45, 0.30}}));

        GraphNode<BayesVariable> rushNode = graph.addNode();
        rushNode.setContent(new BayesVariable<>("RushDet",
            rushNode.getId(), new String[]{"HIGH", "LOW"},
            new double[][]{{0.85, 0.15}, {0.10, 0.90}, {0.30, 0.70}}));

        GraphNode<BayesVariable> ecoNode = graph.addNode();
        ecoNode.setContent(new BayesVariable<>("EcoDet",
            ecoNode.getId(), new String[]{"HIGH", "LOW"},
            new double[][]{{0.08, 0.92}, {0.90, 0.10}, {0.25, 0.75}}));

        graph.addEdge(stratNode, rushNode);
        graph.addEdge(stratNode, ecoNode);

        engine = new EvidenceFusionEngine();
        engine.initialize(graph, "Strategy");
    }

    @Test
    void posteriorSumsToOne() {
        engine.setEvidence("RushDet", new double[]{0.8, 0.2});
        engine.setEvidence("EcoDet", new double[]{0.1, 0.9});
        engine.update();

        double[] posterior = engine.marginalize();
        double sum = 0;
        for (double p : posterior) sum += p;
        assertEquals(1.0, sum, 0.001);
    }

    @Test
    void rushEvidenceShiftsPosteriorToRush() {
        engine.setEvidence("RushDet", new double[]{0.9, 0.1});
        engine.setEvidence("EcoDet", new double[]{0.1, 0.9});
        engine.update();

        double[] posterior = engine.marginalize();
        assertTrue(posterior[0] > posterior[1],
            "RUSH (idx 0) should be more likely than MACRO (idx 1)");
    }

    @Test
    void resetClearsPosteriorToUniform() {
        engine.setEvidence("RushDet", new double[]{0.9, 0.1});
        engine.update();

        engine.reset();
        engine.update();

        double[] posterior = engine.marginalize();
        assertEquals(0.25, posterior[0], 0.05, "RUSH prior ~ 0.25");
        assertEquals(0.45, posterior[1], 0.05, "MACRO prior ~ 0.45");
    }

    @Test
    void getOutcomeNames() {
        String[] names = engine.getOutcomeNames();
        assertArrayEquals(new String[]{"RUSH", "MACRO", "TECH"}, names);
    }
}
