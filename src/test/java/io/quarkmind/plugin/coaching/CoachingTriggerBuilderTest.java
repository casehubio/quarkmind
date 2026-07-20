package io.quarkmind.plugin.coaching;

import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CoachingTriggerBuilderTest {

    private CoachingTriggerBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CoachingTriggerBuilder();
    }

    @Test
    void noMoments_returnsEmpty() {
        var ctx = new MapCaseContext(Map.of());
        assertThat(builder.build(ctx, 100)).isEmpty();
    }

    @Test
    void crisisMoment_returnsTriggerWithCrisisTier() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 100, Map.of()))));
        var result = builder.build(ctx, 100);
        assertThat(result).containsKey(QuarkMindCaseFile.COACHING_TRIGGER);
        @SuppressWarnings("unchecked")
        var trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COACHING_TRIGGER);
        assertThat(trigger.get("urgencyTier")).isEqualTo("CRISIS");
    }

    @Test
    void strategicMoment_returnsStrategicTier() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.TECH_TRANSITION_DETECTED, 100, Map.of()))));
        var result = builder.build(ctx, 100);
        assertThat(result).containsKey(QuarkMindCaseFile.COACHING_TRIGGER);
        @SuppressWarnings("unchecked")
        var trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COACHING_TRIGGER);
        assertThat(trigger.get("urgencyTier")).isEqualTo("STRATEGIC");
    }

    @Test
    void economicMoment_returnsEconomicTier() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SUPPLY_BLOCK, 100, Map.of()))));
        var result = builder.build(ctx, 100);
        assertThat(result).containsKey(QuarkMindCaseFile.COACHING_TRIGGER);
        @SuppressWarnings("unchecked")
        var trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COACHING_TRIGGER);
        assertThat(trigger.get("urgencyTier")).isEqualTo("ECONOMIC");
    }

    @Test
    void unmappedMoment_scoutLost_returnsEmpty() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SCOUT_LOST, 100, Map.of()))));
        assertThat(builder.build(ctx, 100)).isEmpty();
    }

    @Test
    void cooldown_sameTierSuppressed() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SUPPLY_BLOCK, 100, Map.of()))));
        assertThat(builder.build(ctx, 100)).isNotEmpty();
        assertThat(builder.build(ctx, 150)).isEmpty();
    }

    @Test
    void cooldown_expiresAfterFullWindow() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SUPPLY_BLOCK, 100, Map.of()))));
        assertThat(builder.build(ctx, 100)).isNotEmpty();
        assertThat(builder.build(ctx, 211)).isNotEmpty();
    }

    @Test
    void cooldown_crisisPreemptsLowerTier() {
        var economicCtx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SUPPLY_BLOCK, 100, Map.of()))));
        builder.build(economicCtx, 100);

        var crisisCtx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 110, Map.of()))));
        assertThat(builder.build(crisisCtx, 110)).isNotEmpty();
    }

    @Test
    void cooldown_lowerTierCannotPreemptCrisis() {
        var crisisCtx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.BATTLE_STARTED, 100, Map.of()))));
        builder.build(crisisCtx, 100);

        var economicCtx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SUPPLY_BLOCK, 110, Map.of()))));
        assertThat(builder.build(economicCtx, 110)).isEmpty();
    }

    @Test
    void cooldown_resetOnGameStarted() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SUPPLY_BLOCK, 100, Map.of()))));
        builder.build(ctx, 100);
        builder.onGameStarted(null);
        assertThat(builder.build(ctx, 101)).isNotEmpty();
    }

    @Test
    void multipleMoments_highestUrgencyWins() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(
                new GameMoment(GameMomentType.SUPPLY_BLOCK, 100, Map.of()),
                new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 100, Map.of()))));
        var result = builder.build(ctx, 100);
        @SuppressWarnings("unchecked")
        var trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COACHING_TRIGGER);
        assertThat(trigger.get("urgencyTier")).isEqualTo("CRISIS");
    }
}
