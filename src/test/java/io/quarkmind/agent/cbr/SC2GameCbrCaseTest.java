package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.FeatureValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SC2GameCbrCaseTest {

    @Test
    void cbrType() {
        var c = new SC2GameCbrCase("vs ZERG_ROACH_RUSH (PvZ)", "strategy.early-pressure",
                                   null, null, Map.of());
        assertThat(c.cbrType()).isEqualTo("sc2-strategy");
    }

    @Test
    void withOutcome() {
        var c = new SC2GameCbrCase("vs ZERG_ROACH_RUSH (PvZ)", "strategy.early-pressure",
                                   null, null, Map.of());
        var updated = c.withOutcome("WIN", 0.85);
        assertThat(updated.outcome()).isEqualTo("WIN");
        assertThat(updated.confidence()).isEqualTo(0.85);
        assertThat(updated.problem()).isEqualTo("vs ZERG_ROACH_RUSH (PvZ)");
        assertThat(updated.solution()).isEqualTo("strategy.early-pressure");
    }

    @Test
    void withFeatures() {
        var c = new SC2GameCbrCase("problem", "solution", null, null, Map.of());
        Map<String, FeatureValue> features = Map.of(
                "enemy_archetype", FeatureValue.string("ZERG_ROACH_RUSH"),
                "enemy_race", FeatureValue.string("ZERG"));
        var updated = c.withFeatures(features);
        assertThat(updated.features()).containsKey("enemy_archetype");
        assertThat(updated.features().get("enemy_archetype"))
                .isEqualTo(FeatureValue.string("ZERG_ROACH_RUSH"));
    }

    @Test
    void featureMap_immutable() {
        Map<String, FeatureValue> features = new java.util.HashMap<>(
                Map.of("k", FeatureValue.string("v")));
        var c = new SC2GameCbrCase("p", "s", null, null, features);
        assertThatThrownBy(() -> c.features().put("x", FeatureValue.string("y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void problemRequired() {
        assertThatThrownBy(() -> new SC2GameCbrCase(null, "s", null, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void solutionRequired() {
        assertThatThrownBy(() -> new SC2GameCbrCase("p", null, null, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildForGame() {
        var c = SC2GameCbrCase.buildForGame(
                "ZERG_ROACH_RUSH", "ZERG", "PvZ", 0.82, "strategy.early-pressure");
        assertThat(c.problem()).isEqualTo("vs ZERG_ROACH_RUSH (PvZ)");
        assertThat(c.solution()).isEqualTo("strategy.early-pressure");
        assertThat(c.outcome()).isNull();
        assertThat(c.confidence()).isNull();
        assertThat(c.features()).containsEntry("enemy_archetype", FeatureValue.string("ZERG_ROACH_RUSH"));
        assertThat(c.features()).containsEntry("enemy_race", FeatureValue.string("ZERG"));
        assertThat(c.features()).containsEntry("matchup", FeatureValue.string("PvZ"));
        assertThat(c.features()).containsEntry("assessment_confidence", FeatureValue.number(0.82));
    }

    @Test
    void buildForGameEnriched_allFeaturesPopulated() {
        var enrichment = new EnrichedGameData(
                List.of("EARLY_MACRO", "MID_SKIRMISH"), 8, "Game progression: EARLY_MACRO -> MID_SKIRMISH", 12.5,
                3, 0.4, 0.3,
                2, 42, 0.5, 1,
                OptionalDouble.of(2.1), OptionalDouble.of(1.5), 0.82,
                "ZERG_ROACH_RUSH", 0, 0, 0.0);
        var c = SC2GameCbrCase.buildForGameEnriched(
                "ZERG_ROACH_RUSH", "ZERG", "PvZ", 0.82, "strategy.early-pressure", enrichment);

        assertThat(c.problem()).isEqualTo("vs ZERG_ROACH_RUSH (PvZ)");
        assertThat(c.solution()).isEqualTo("strategy.early-pressure");
        assertThat(c.features()).containsEntry("enemy_archetype", FeatureValue.string("ZERG_ROACH_RUSH"));
        assertThat(c.features()).containsEntry("phase_sequence", FeatureValue.stringList(List.of("EARLY_MACRO", "MID_SKIRMISH")));
        assertThat(c.features()).containsEntry("moment_count", FeatureValue.number(8));
        assertThat(c.features()).containsEntry("battle_count", FeatureValue.number(3));
        assertThat(c.features()).containsEntry("dominance_overall", FeatureValue.number(0.3));
        assertThat(c.features()).containsEntry("expansion_count", FeatureValue.number(2));
        assertThat(c.features()).containsEntry("worker_count_final", FeatureValue.number(42));
        assertThat(c.features()).containsEntry("supply_block_count", FeatureValue.number(1));
        assertThat(c.features()).containsEntry("first_contact_minute", FeatureValue.number(2.1));
        assertThat(c.features()).containsEntry("scout_dispatch_minute", FeatureValue.number(1.5));
        assertThat(c.features()).containsEntry("opponent_id", FeatureValue.string("ZERG_ROACH_RUSH"));
        assertThat(c.features()).containsEntry("archetype_confidence", FeatureValue.number(0.82));
        assertThat(c.features()).containsEntry("arc_narrative", FeatureValue.string("Game progression: EARLY_MACRO -> MID_SKIRMISH"));
    }

    @Test
    void buildForGameEnriched_optionalTimingFeaturesOmittedWhenEmpty() {
        var enrichment = new EnrichedGameData(
                List.of("EARLY_MACRO"), 2, "", 5.0,
                0, 0.0, 0.0,
                1, 22, 0.0, 0,
                OptionalDouble.empty(), OptionalDouble.empty(), 0.5,
                "mock-opponent", 0, 0, 0.0);
        var c = SC2GameCbrCase.buildForGameEnriched(
                "TERRAN_2RAX_MARINE", "TERRAN", "PvT", 0.5, "strategy.drools", enrichment);

        assertThat(c.features()).doesNotContainKey("first_contact_minute");
        assertThat(c.features()).doesNotContainKey("scout_dispatch_minute");
        assertThat(c.features()).containsKey("moment_count");
        assertThat(c.features()).containsKey("opponent_id");
        assertThat(c.features()).doesNotContainKey("arc_narrative");
    }

    @Test
    void buildForGameEnriched_withOutcomePreservesEnrichment() {
        var enrichment = new EnrichedGameData(
                List.of("EARLY_MACRO"), 5, "narrative", 10.0,
                2, 0.3, 0.2,
                1, 30, 0.1, 0,
                OptionalDouble.of(3.0), OptionalDouble.empty(), 0.7,
                "ZERG_MASS_LING", 0, 0, 0.0);
        var c = SC2GameCbrCase.buildForGameEnriched(
                "ZERG_MASS_LING", "ZERG", "PvZ", 0.7, "strategy.early-pressure", enrichment);
        var updated = (SC2GameCbrCase) c.withOutcome("WIN", 0.9);

        assertThat(updated.outcome()).isEqualTo("WIN");
        assertThat(updated.features()).containsEntry("battle_count", FeatureValue.number(2));
        assertThat(updated.features()).containsEntry("opponent_id", FeatureValue.string("ZERG_MASS_LING"));
    }


    @Test
    void buildForGameEnriched_includesEngagementFeatures() {
        var enrichment = new EnrichedGameData(
                List.of("PASSIVE"), 3, "arc", 10.0,
                2, 0.6, 0.5, 1, 12, 0.4, 0,
                OptionalDouble.of(2.0), OptionalDouble.of(1.0), 0.8, "opp-hash",
                2, 1, 1.5);

        var cbrCase = SC2GameCbrCase.buildForGameEnriched(
                "ZERG_ROACH_RUSH", "ZERG", "PvZ", 0.8, "adaptive", enrichment);

        assertThat(cbrCase.features()).containsEntry("engagements_won", FeatureValue.number(2));
        assertThat(cbrCase.features()).containsEntry("engagements_lost", FeatureValue.number(1));
        assertThat(cbrCase.features()).containsEntry("unit_trade_ratio", FeatureValue.number(1.5));
    }
}
