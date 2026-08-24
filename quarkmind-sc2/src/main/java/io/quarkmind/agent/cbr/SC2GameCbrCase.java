package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.quarkmind.domain.TimelineObservation;

import java.util.List;
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

        features.put("engagements_won", FeatureValue.number(e.engagementsWon()));
        features.put("engagements_lost", FeatureValue.number(e.engagementsLost()));
        features.put("unit_trade_ratio", FeatureValue.number(e.unitTradeRatio()));

        features.put("scouting_convergence", FeatureValue.number(e.scoutingConvergence()));
        features.put("assessment_stable", FeatureValue.number(e.assessmentStable() ? 1.0 : 0.0));

        return new SC2GameCbrCase(
                "vs " + archetypeName + " (" + matchup + ")",
                strategyId, null, null, features);
    }

    public static SC2GameCbrCase buildForGameEnriched(
            String archetypeName, String raceName, String matchup,
            double assessmentConfidence, String strategyId,
            EnrichedGameData e, List<TimelineObservation> timeline) {
        SC2GameCbrCase base = buildForGameEnriched(
                archetypeName, raceName, matchup,
                assessmentConfidence, strategyId, e);
        if (timeline == null || timeline.isEmpty()) {
            return base;
        }
        var features = new java.util.HashMap<>(base.features());
        List<Map<String, FeatureValue>> observations = timeline.stream()
                                                               .map(t -> Map.<String, FeatureValue>of(
                                                                       "minute", FeatureValue.number(t.minute()),
                                                                       "our_workers", FeatureValue.number(t.ourWorkers()),
                                                                       "our_minerals", FeatureValue.number(t.ourMinerals()),
                                                                       "our_army_supply", FeatureValue.number(t.ourArmySupply())))
                                                               .toList();
        features.put("timeline", FeatureValue.structList(observations));
        return (SC2GameCbrCase) base.withFeatures(features);
    }

    @Override
    public String cbrType() {return CBR_TYPE;}

    @Override
    public CbrCase withOutcome(String outcome, Double confidence) {
        return new SC2GameCbrCase(problem, solution, outcome, confidence, features);
    }

    @Override
    public CbrCase withFeatures(Map<String, FeatureValue> features) {
        return new SC2GameCbrCase(problem, solution, outcome, confidence, features);
    }


}
