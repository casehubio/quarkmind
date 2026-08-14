package io.quarkmind.agent;

import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;

import java.util.List;

public class ScoutingConvergenceEvaluator {

    public record Result(double convergence, boolean stable) {}

    public static Result evaluate(StrategyArchetype initialArchetype,
                                  List<PatternAssessment> finalAssessments) {
        if (finalAssessments.isEmpty()) {
            return new Result(0.0, false);
        }
        StrategyArchetype finalArchetype = finalAssessments.getFirst().archetype();

        double convergence;
        if (initialArchetype == finalArchetype) {
            convergence = 1.0;
        } else if (initialArchetype.race() == finalArchetype.race()
                   && initialArchetype.category() == finalArchetype.category()) {
            convergence = 0.5;
        } else {
            convergence = 0.0;
        }
        return new Result(convergence, convergence >= 0.5);
    }
}
