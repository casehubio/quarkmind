package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
@Startup
public class SC2CbrSchemaRegistrar {

    private static final Logger log = Logger.getLogger(SC2CbrSchemaRegistrar.class);

    @Inject CbrCaseMemoryStore cbrStore;

    @PostConstruct
    void register() {
        CbrFeatureSchema strategySchema = CbrFeatureSchema.of(
                SC2GameCbrCase.CBR_TYPE,
                // Tier 1
                FeatureField.categorical("enemy_archetype"),
                FeatureField.categorical("enemy_race"),
                FeatureField.categorical("matchup"),
                FeatureField.numeric("assessment_confidence", 0.0, 1.0),
                // #215 — Event hierarchy
                FeatureField.categoricalList("phase_sequence"),
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
                FeatureField.categorical("opponent_id")
                                                             );
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
                  SC2AdvisoryCbrCase.CBR_TYPE, advisorySchema.fields().size());}
}
