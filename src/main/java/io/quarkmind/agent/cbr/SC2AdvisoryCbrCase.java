package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.FeatureValue;

import java.util.Map;
import java.util.Objects;

public record SC2AdvisoryCbrCase(
        String problem,
        String solution,
        String outcome,
        Double confidence,
        Map<String, FeatureValue> features
) implements CbrCase {

    public static final String CBR_TYPE = "sc2-advisory";

    public SC2AdvisoryCbrCase {
        Objects.requireNonNull(problem, "problem required");
        Objects.requireNonNull(solution, "solution required");
        Objects.requireNonNull(features, "features required");
        features = Map.copyOf(features);
    }

    @Override
    public String cbrType() { return CBR_TYPE; }

    @Override
    public CbrCase withOutcome(String outcome, Double confidence) {
        return new SC2AdvisoryCbrCase(problem, solution, outcome, confidence, features);
    }

    @Override
    public CbrCase withFeatures(Map<String, FeatureValue> features) {
        return new SC2AdvisoryCbrCase(problem, solution, outcome, confidence, features);
    }

    public static SC2AdvisoryCbrCase buildForAdvisory(
            String advisorId, String archetypeName, String raceName,
            String matchup, String strategyId, String gamePhase) {
        return new SC2AdvisoryCbrCase(
                "advisory in " + matchup + " vs " + archetypeName,
                advisorId,
                null, null,
                Map.of(
                        "enemy_archetype", FeatureValue.string(archetypeName),
                        "enemy_race", FeatureValue.string(raceName),
                        "matchup", FeatureValue.string(matchup),
                        "strategy_id", FeatureValue.string(strategyId),
                        "game_phase", FeatureValue.string(gamePhase != null ? gamePhase : "unknown")
                ));
    }
}
