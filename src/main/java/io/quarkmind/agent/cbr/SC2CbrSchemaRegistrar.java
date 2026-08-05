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
                FeatureField.categorical("enemy_archetype"),
                FeatureField.categorical("enemy_race"),
                FeatureField.categorical("matchup"),
                FeatureField.numeric("assessment_confidence", 0.0, 1.0)
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
                  SC2AdvisoryCbrCase.CBR_TYPE, advisorySchema.fields().size());
    }
}
