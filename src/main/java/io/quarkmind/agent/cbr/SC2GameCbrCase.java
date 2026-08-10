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

    public static SC2GameCbrCase buildForGameEnriched(
            String archetypeName, String raceName, String matchup,
            double assessmentConfidence, String strategyId,
            EnrichedGameData e) {
        var features = new java.util.HashMap<String, FeatureValue>();
        features.put("enemy_archetype", FeatureValue.string(archetypeName));
        features.put("enemy_race", FeatureValue.string(raceName));
        features.put("matchup", FeatureValue.string(matchup));
        features.put("assessment_confidence", FeatureValue.number(assessmentConfidence));

        features.put("phase_sequence", FeatureValue.stringList(e.phaseSequence()));
        features.put("phase_count", FeatureValue.number(e.phaseSequence().stream().distinct().count()));
        features.put("moment_count", FeatureValue.number(e.momentCount()));
        if (e.arcNarrative() != null && !e.arcNarrative().isEmpty()) {
            features.put("arc_narrative", FeatureValue.string(e.arcNarrative()));
        }
        features.put("game_duration_minutes", FeatureValue.number(e.gameDurationMinutes()));

        features.put("battle_count", FeatureValue.number(e.battleCount()));
        features.put("dominance_army", FeatureValue.number(e.dominanceArmy()));
        features.put("dominance_overall", FeatureValue.number(e.dominanceOverall()));

        features.put("expansion_count", FeatureValue.number(e.expansionCount()));
        features.put("worker_count_final", FeatureValue.number(e.workerCountFinal()));
        features.put("dominance_economy", FeatureValue.number(e.dominanceEconomy()));
        features.put("supply_block_count", FeatureValue.number(e.supplyBlockCount()));

        e.firstContactMinute().ifPresent(v -> features.put("first_contact_minute", FeatureValue.number(v)));
        e.scoutDispatchMinute().ifPresent(v -> features.put("scout_dispatch_minute", FeatureValue.number(v)));
        features.put("archetype_confidence", FeatureValue.number(e.archetypeConfidence()));

        features.put("opponent_id", FeatureValue.string(e.opponentId()));

        return new SC2GameCbrCase(
                "vs " + archetypeName + " (" + matchup + ")",
                strategyId, null, null, features);
    }

}
