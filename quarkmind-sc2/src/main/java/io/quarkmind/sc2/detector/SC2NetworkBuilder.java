package io.quarkmind.sc2.detector;

import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.BayesNetworkBuilder;

import java.util.List;
import java.util.Map;

public class SC2NetworkBuilder {

    public static BayesNetwork build(
            Map<String, Double> priors,
            List<String> detectorNames,
            Map<String, Map<String, Double>> cpts) {

        String[] archetypes = sortedArchetypes(priors);

        double[] priorArray = new double[archetypes.length];
        for (int i = 0; i < archetypes.length; i++) {
            priorArray[i] = priors.getOrDefault(archetypes[i], 1.0 / archetypes.length);
        }

        var builder = BayesNetworkBuilder.create();
        builder.variable("Strategy", archetypes).prior(priorArray);

        for (String detName : detectorNames) {
            Map<String, Double> cpt     = cpts.get(detName);
            double[]            flatCpt = new double[archetypes.length * 2];
            for (int i = 0; i < archetypes.length; i++) {
                double pHigh = cpt.getOrDefault(archetypes[i], 0.5);
                pHigh              = Math.max(0.01, Math.min(0.99, pHigh));
                flatCpt[i * 2]     = pHigh;
                flatCpt[i * 2 + 1] = 1.0 - pHigh;
            }
            builder.variable(detName, "HIGH", "LOW").given("Strategy").cpt(flatCpt);
        }

        return builder.build();
    }

    static String[] sortedArchetypes(Map<String, Double> priors) {
        return priors.keySet().stream().sorted().toArray(String[]::new);
    }
}
