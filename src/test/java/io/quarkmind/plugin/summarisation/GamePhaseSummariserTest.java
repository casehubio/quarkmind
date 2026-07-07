package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GamePhaseSummariserTest {

    private static final EventLevel L2 = new EventLevel("moment", 2);

    private final GamePhaseSummariser summariser = new GamePhaseSummariser();

    @Test
    void multipleBattles_classifiesAsMidSkirmish() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.BATTLE_STARTED, 100), 100, L2),
            new LevelEvent<>(moment(GameMomentType.BATTLE_ENDED, 150), 150, L2),
            new LevelEvent<>(moment(GameMomentType.BATTLE_STARTED, 180), 180, L2));
        var phases = summariser.summarise(batch).toCompletableFuture().join();
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("MID_SKIRMISH");
    }

    @Test
    void nexusUnderAttack_classifiesAsDefensiveHold() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.NEXUS_UNDER_ATTACK, 200), 200, L2));
        var phases = summariser.summarise(batch).toCompletableFuture().join();
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("DEFENSIVE_HOLD");
    }

    @Test
    void noCombatMoments_classifiesAsEarlyMacro() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.TECH_TRANSITION_DETECTED, 50), 50, L2));
        var phases = summariser.summarise(batch).toCompletableFuture().join();
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("EARLY_MACRO");
    }

    @Test
    void economicCrisis_classifiesAsEarlyAggression() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.ECONOMIC_CRISIS, 100), 100, L2),
            new LevelEvent<>(moment(GameMomentType.BATTLE_STARTED, 120), 120, L2));
        var phases = summariser.summarise(batch).toCompletableFuture().join();
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("EARLY_AGGRESSION");
    }

    @Test
    void emptyBatch_returnsEmpty() {
        assertThat(summariser.summarise(List.of()).toCompletableFuture().join()).isEmpty();
    }

    private static GameMoment moment(GameMomentType type, long frame) {
        return new GameMoment(type, frame, Map.of());
    }
}
