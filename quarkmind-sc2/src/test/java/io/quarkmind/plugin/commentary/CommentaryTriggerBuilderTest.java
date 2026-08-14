package io.quarkmind.plugin.commentary;

import io.casehub.api.context.CaseContext;
import io.quarkmind.agency.context.MapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommentaryTriggerBuilder}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>All GameMomentType values trigger commentary (not selective like advisory)</li>
 *   <li>Cooldown enforcement (110 frames ~5s)</li>
 *   <li>Batching of multiple moments into single trigger</li>
 *   <li>Cooldown reset on GameStarted</li>
 * </ul>
 *
 * <p>Refs #181 (Task 5)
 */
class CommentaryTriggerBuilderTest {

    private CommentaryTriggerBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CommentaryTriggerBuilder();
    }

    @Test
    void momentsPresent_returnsTriggerMap() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.BATTLE_STARTED, 2240L, Map.of()))
        ));

        Map<String, Object> result = builder.build(ctx, 2240L);

        assertTrue(result.containsKey(QuarkMindCaseFile.COMMENTARY_TRIGGER));
        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
        assertEquals(2240L, trigger.get("gameFrame"));
        @SuppressWarnings("unchecked")
        List<String> momentTypes = (List<String>) trigger.get("momentTypes");
        assertEquals(List.of("BATTLE_STARTED"), momentTypes);
    }

    @Test
    void emptyMoments_returnsEmptyMap() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST, List.of()
        ));

        Map<String, Object> result = builder.build(ctx, 1000L);

        assertTrue(result.isEmpty());
    }

    @Test
    void nullMomentsList_returnsEmptyMap() {
        CaseContext ctx = new MapCaseContext(Map.of());

        Map<String, Object> result = builder.build(ctx, 1000L);

        assertTrue(result.isEmpty());
    }

    @Test
    void cooldownEnforcement_secondCallWithin110Frames_returnsEmpty() {
        CaseContext ctx1 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.BATTLE_STARTED, 1000L, Map.of()))
        ));
        CaseContext ctx2 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 1050L, Map.of()))
        ));

        // First call at frame 1000 — should fire
        Map<String, Object> result1 = builder.build(ctx1, 1000L);
        assertFalse(result1.isEmpty());

        // Second call at frame 1050 (50 frames later, < 110) — should be suppressed
        Map<String, Object> result2 = builder.build(ctx2, 1050L);
        assertTrue(result2.isEmpty());
    }

    @Test
    void cooldownExpired_secondCallAfter110Frames_fires() {
        CaseContext ctx1 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.BATTLE_STARTED, 1000L, Map.of()))
        ));
        CaseContext ctx2 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 1120L, Map.of()))
        ));

        // First call at frame 1000
        Map<String, Object> result1 = builder.build(ctx1, 1000L);
        assertFalse(result1.isEmpty());

        // Second call at frame 1120 (120 frames later, >= 110) — should fire
        Map<String, Object> result2 = builder.build(ctx2, 1120L);
        assertFalse(result2.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) result2.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
        assertEquals(1120L, trigger.get("gameFrame"));
    }

    @Test
    void multipleMoments_batchedIntoSingleTrigger() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(
                new GameMoment(GameMomentType.BATTLE_STARTED, 2000L, Map.of()),
                new GameMoment(GameMomentType.FIRST_CONTACT, 2020L, Map.of()),
                new GameMoment(GameMomentType.SUPPLY_BLOCK, 2040L, Map.of())
            )
        ));

        Map<String, Object> result = builder.build(ctx, 2040L);

        assertEquals(1, result.size()); // Single trigger key
        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
        @SuppressWarnings("unchecked")
        List<String> momentTypes = (List<String>) trigger.get("momentTypes");
        assertEquals(3, momentTypes.size());
        assertTrue(momentTypes.contains("BATTLE_STARTED"));
        assertTrue(momentTypes.contains("FIRST_CONTACT"));
        assertTrue(momentTypes.contains("SUPPLY_BLOCK"));
    }

    @Test
    void allGameMomentTypes_trigger() {
        // Commentary reacts to ALL moment types (unlike advisory which is selective)
        for (GameMomentType type : GameMomentType.values()) {
            CommentaryTriggerBuilder freshBuilder = new CommentaryTriggerBuilder();
            CaseContext ctx = new MapCaseContext(Map.of(
                QuarkMindCaseFile.MOMENTS_LATEST,
                List.of(new GameMoment(type, 1000L, Map.of()))
            ));

            Map<String, Object> result = freshBuilder.build(ctx, 1000L);

            assertFalse(result.isEmpty(), "Moment type " + type + " should trigger");
            @SuppressWarnings("unchecked")
            Map<String, Object> trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
            @SuppressWarnings("unchecked")
            List<String> momentTypes = (List<String>) trigger.get("momentTypes");
            assertEquals(List.of(type.name()), momentTypes);
        }
    }

    @Test
    void triggerPayload_includesGameState() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.BATTLE_STARTED, 2240L, Map.of())),
            QuarkMindCaseFile.MINERALS, 450,
            QuarkMindCaseFile.SUPPLY_USED, 35,
            QuarkMindCaseFile.SUPPLY_CAP, 46,
            QuarkMindCaseFile.ARMY, 12
        ));

        Map<String, Object> result = builder.build(ctx, 2240L);

        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
        assertEquals(450, trigger.get("minerals"));
        assertEquals(35, trigger.get("supplyUsed"));
        assertEquals(46, trigger.get("supplyCap"));
        assertEquals(12, trigger.get("army"));
    }

    @Test
    void triggerPayload_handlesNullGameState() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.BATTLE_STARTED, 2240L, Map.of()))
            // No game state keys
        ));

        Map<String, Object> result = builder.build(ctx, 2240L);

        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) result.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
        assertEquals(0, trigger.get("minerals"));
        assertEquals(0, trigger.get("supplyUsed"));
        assertEquals(0, trigger.get("supplyCap"));
        assertEquals(0, trigger.get("army"));
    }

    @Test
    void gameStarted_resetsCooldown() {
        CaseContext ctx1 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.BATTLE_STARTED, 5000L, Map.of()))
        ));

        // First fire at frame 5000
        Map<String, Object> result1 = builder.build(ctx1, 5000L);
        assertFalse(result1.isEmpty());

        // GameStarted event — resets cooldown
        builder.onGameStarted(new GameStarted());

        // Immediate fire should work (cooldown reset)
        CaseContext ctx2 = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.FIRST_CONTACT, 100L, Map.of()))
        ));
        Map<String, Object> result2 = builder.build(ctx2, 100L);
        assertFalse(result2.isEmpty());
    }
}
