package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.FeatureValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
