package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.SimilaritySpec;
import io.casehub.neocortex.memory.cbr.WarpingConstraint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SC2CbrSchemaRegistrarTest {

    @Test
    void strategySchema_hasTimelineTimeSeries() {
        var schema = SC2CbrSchemaRegistrar.buildStrategySchema();
        var timelineField = schema.fields().stream()
                .filter(f -> f.name().equals("timeline"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("timeline field not found"));

        assertThat(timelineField).isInstanceOf(FeatureField.TimeSeries.class);
        var ts = (FeatureField.TimeSeries) timelineField;
        assertThat(ts.timestampField()).isEqualTo("minute");
        assertThat(ts.similaritySpec()).isInstanceOf(SimilaritySpec.DtwSpec.class);
        var dtw = (SimilaritySpec.DtwSpec) ts.similaritySpec();
        assertThat(dtw.constraint()).isInstanceOf(WarpingConstraint.SakoeChibaBand.class);
        assertThat(((WarpingConstraint.SakoeChibaBand) dtw.constraint()).windowSize()).isEqualTo(3);
        assertThat(ts.innerFields()).hasSize(4);
        assertThat(ts.innerFields().stream().map(FeatureField::name).toList())
                .containsExactly("minute", "our_workers", "our_minerals", "our_army_supply");
    }

    @Test
    void strategySchema_phaseSequenceIsDiscreteSequence() {
        var schema = SC2CbrSchemaRegistrar.buildStrategySchema();
        var phaseField = schema.fields().stream()
                .filter(f -> f.name().equals("phase_sequence"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("phase_sequence field not found"));

        assertThat(phaseField).isInstanceOf(FeatureField.DiscreteSequence.class);
        var ds = (FeatureField.DiscreteSequence) phaseField;
        assertThat(ds.similaritySpec()).isInstanceOf(SimilaritySpec.EditDistanceSpec.class);
        var ed = (SimilaritySpec.EditDistanceSpec) ds.similaritySpec();
        assertThat(ed.substitutionSimilarities()).containsKey("EARLY_MACRO");
        assertThat(ed.substitutionSimilarities()).containsKey("MID_SKIRMISH");
        assertThat(ed.substitutionSimilarities()).containsKey("EARLY_AGGRESSION");
        assertThat(ed.substitutionSimilarities()).containsKey("DEFENSIVE_HOLD");
        assertThat(ed.substitutionSimilarities()).containsKey("TRANSITIONING");
    }

    @Test
    void strategySchema_retainsAllExistingFields() {
        var schema = SC2CbrSchemaRegistrar.buildStrategySchema();
        var fieldNames = schema.fields().stream().map(FeatureField::name).toList();
        assertThat(fieldNames).contains(
                "enemy_archetype", "enemy_race", "matchup", "assessment_confidence",
                "phase_count", "moment_count", "arc_narrative", "game_duration_minutes",
                "battle_count", "dominance_army", "dominance_overall",
                "expansion_count", "worker_count_final", "dominance_economy", "supply_block_count",
                "first_contact_minute", "scout_dispatch_minute", "archetype_confidence",
                "opponent_id");
    }

    @Test
    void strategySchema_constructsWithoutError() {
        assertThatCode(SC2CbrSchemaRegistrar::buildStrategySchema).doesNotThrowAnyException();
    }
}
