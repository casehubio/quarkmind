package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.FeatureValue;

import java.util.Map;
import java.util.Objects;

public record SC2GameCbrCase(
        String problem,
        String solution,
        String outcome,
        Double confidence,
        Map<String, FeatureValue> features
) implements CbrCase {

    public static final String CBR_TYPE = "sc2-strategy";

    public SC2GameCbrCase {
        Objects.requireNonNull(problem, "problem required");
        Objects.requireNonNull(solution, "solution required");
        Objects.requireNonNull(features, "features required");
        features = Map.copyOf(features);
    }

    @Override
    public String cbrType() { return CBR_TYPE; }

    @Override
    public CbrCase withOutcome(String outcome, Double confidence) {
        return new SC2GameCbrCase(problem, solution, outcome, confidence, features);
    }

    @Override
    public CbrCase withFeatures(Map<String, FeatureValue> features) {
        return new SC2GameCbrCase(problem, solution, outcome, confidence, features);
    }

    public static SC2GameCbrCase buildForGame(
            String archetypeName, String raceName, String matchup,
            double assessmentConfidence, String strategyId) {
        return new SC2GameCbrCase(
                "vs " + archetypeName + " (" + matchup + ")",
                strategyId,
                null, null,
                Map.of(
                        "enemy_archetype", FeatureValue.string(archetypeName),
                        "enemy_race", FeatureValue.string(raceName),
                        "matchup", FeatureValue.string(matchup),
                        "assessment_confidence", FeatureValue.number(assessmentConfidence)
                ));
    }
}
