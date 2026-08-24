package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.SimilaritySpec;
import io.casehub.neocortex.memory.cbr.WarpingConstraint;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;

import java.util.HashMap;
import java.util.Map;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
@Startup
public class SC2CbrSchemaRegistrar {

    private static final Logger log = Logger.getLogger(SC2CbrSchemaRegistrar.class);

    @Inject
    CbrCaseMemoryStore cbrStore;

    static CbrFeatureSchema buildStrategySchema() {
        return CbrFeatureSchema.of(
                SC2GameCbrCase.CBR_TYPE,
                // Tier 1
                FeatureField.categorical("enemy_archetype"),
                FeatureField.categorical("enemy_race"),
                FeatureField.categorical("matchup"),
                FeatureField.numeric("assessment_confidence", 0.0, 1.0),
                // #215 — Event hierarchy
                FeatureField.discreteSequence("phase_sequence",
                                              new SimilaritySpec.EditDistanceSpec(phaseSubstitutionCosts(), 1.0, 1.0)),
                FeatureField.numeric("phase_count", 0, 20),
                FeatureField.numeric("moment_count", 0, 50),
                FeatureField.text("arc_narrative"),
                FeatureField.numeric("game_duration_minutes", 0, 30),
                // #217 — Tactical
                FeatureField.numeric("battle_count", 0, 20),
                FeatureField.numeric("dominance_army", -1.0, 1.0),
                FeatureField.numeric("dominance_overall", -1.0, 1.0),
                // #218 — Economics
                FeatureField.numeric("expansion_count", 0, 8),
                FeatureField.numeric("worker_count_final", 0, 80),
                FeatureField.numeric("dominance_economy", -1.0, 1.0),
                FeatureField.numeric("supply_block_count", 0, 20),
                // #219 — Scouting
                FeatureField.numeric("first_contact_minute", 0, 15),
                FeatureField.numeric("scout_dispatch_minute", 0, 10),
                FeatureField.numeric("archetype_confidence", 0, 1.0),
                // #220 — Opponent
                FeatureField.categorical("opponent_id"),
                // #222 — Temporal
                FeatureField.timeSeries("timeline", "minute",
                                        new SimilaritySpec.DtwSpec(new WarpingConstraint.SakoeChibaBand(3)),
                                        FeatureField.numeric("minute", 0, 30),
                                        FeatureField.numeric("our_workers", 0, 80),
                                        FeatureField.numeric("our_minerals", 0, 5000),
                                        FeatureField.numeric("our_army_supply", 0, 200))
                                  );
    }

    private static Map<String, Map<String, Double>> phaseSubstitutionCosts() {
        var costs = new HashMap<String, Map<String, Double>>();
        costs.put("EARLY_MACRO", Map.of(
                "TRANSITIONING", 0.6, "MID_SKIRMISH", 0.2,
                "EARLY_AGGRESSION", 0.1, "DEFENSIVE_HOLD", 0.2));
        costs.put("TRANSITIONING", Map.of(
                "EARLY_MACRO", 0.6, "MID_SKIRMISH", 0.5,
                "EARLY_AGGRESSION", 0.4, "DEFENSIVE_HOLD", 0.4));
        costs.put("MID_SKIRMISH", Map.of(
                "EARLY_MACRO", 0.2, "TRANSITIONING", 0.5,
                "EARLY_AGGRESSION", 0.6, "DEFENSIVE_HOLD", 0.5));
        costs.put("EARLY_AGGRESSION", Map.of(
                "EARLY_MACRO", 0.1, "TRANSITIONING", 0.4,
                "MID_SKIRMISH", 0.6, "DEFENSIVE_HOLD", 0.4));
        costs.put("DEFENSIVE_HOLD", Map.of(
                "EARLY_MACRO", 0.2, "TRANSITIONING", 0.4,
                "MID_SKIRMISH", 0.5, "EARLY_AGGRESSION", 0.4));
        return Map.copyOf(costs);
    }

    @PostConstruct
    void register() {
        CbrFeatureSchema strategySchema = buildStrategySchema();
        cbrStore.registerSchema(strategySchema);

        CbrFeatureSchema advisorySchema = CbrFeatureSchema.of(
                SC2AdvisoryCbrCase.CBR_TYPE,
                FeatureField.categorical("enemy_archetype"),
                FeatureField.categorical("enemy_race"),
                FeatureField.categorical("matchup"),
                FeatureField.categorical("strategy_id"),
                FeatureField.categorical("game_phase")
                                                             );
        cbrStore.registerSchema(advisorySchema);

        log.infof("[CBR] Registered schemas: '%s' (%d fields), '%s' (%d fields)",
                  SC2GameCbrCase.CBR_TYPE, strategySchema.fields().size(),
                  SC2AdvisoryCbrCase.CBR_TYPE, advisorySchema.fields().size());
    }


}
