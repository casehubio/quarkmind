package io.quarkmind.plugin.commentary;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.summarisation.GameArc;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CommentaryAccumulator}.
 *
 * <p>Tests without CDI — constructs accumulator directly with a mock context holder,
 * publishes moments to the bus, and asserts trigger map generation.
 *
 * <p>Refs #181 Task 6
 */
class CommentaryAccumulatorTest {

    private static final EventLevel LEVEL_2 = new EventLevel("moment", 2);
    private static final long FRAME_672 = 672;  // ~30s minimum time floor

    private EventStreamBus<GameMoment> momentBus;
    private NarrativeContextHolder contextHolder;
    private EventStreamBus<TacticalPosture> phaseBus;
    private EventStreamBus<GameArc> arcBus;
    private CommentaryAccumulator accumulator;

    @BeforeEach
    void setUp() {
        momentBus = new EventStreamBus<>();
        phaseBus = new EventStreamBus<>();
        arcBus = new EventStreamBus<>();
        contextHolder = new NarrativeContextHolder(phaseBus, arcBus);
        contextHolder.init();
        accumulator = new CommentaryAccumulator(momentBus, contextHolder);
        accumulator.init();
    }

    @Test
    void fourMomentsAccumulated_tickReturnsTriggerMap() {
        // Given — 4 moments published
        publishMoment(100, GameMomentType.FIRST_CONTACT);
        publishMoment(200, GameMomentType.BATTLE_STARTED);
        publishMoment(300, GameMomentType.BATTLE_ENDED);
        publishMoment(400, GameMomentType.SUPPLY_BLOCK);

        // Context populated
        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("Mid-game", 300L, "Combat"), 300L, new EventLevel("phase", 3)));

        // When — tick at frame >= minimum floor (672)
        Map<String, Object> result = accumulator.tick(FRAME_672);

        // Then — trigger map returned
        assertThat(result).containsKey(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER);
    }

    @Test
    void windowTimeElapsed_tickReturnsTriggerMap() {
        // Given — one moment at frame 0
        publishMoment(0, GameMomentType.ECONOMIC_CRISIS);

        // When — tick at 1008 frames (window maxAge threshold: 45 * 22.4 = 1008)
        Map<String, Object> result = accumulator.tick(1008);

        // Then — trigger map returned (time threshold met)
        assertThat(result).containsKey(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER);
    }

    @Test
    void minimumTimeFloor_emitsBlocked() {
        // Given — 4 moments accumulated (count threshold met)
        publishMoment(100, GameMomentType.FIRST_CONTACT);
        publishMoment(200, GameMomentType.BATTLE_STARTED);
        publishMoment(300, GameMomentType.BATTLE_ENDED);
        publishMoment(400, GameMomentType.SUPPLY_BLOCK);

        // When — tick BEFORE minimum time floor (671 < 672)
        Map<String, Object> result = accumulator.tick(671);

        // Then — no trigger (blocked by time floor)
        assertThat(result).isEmpty();
    }

    @Test
    void minimumTimeFloor_subsequentEmit_enforced() {
        // Given — first emit at frame 672
        publishMoment(100, GameMomentType.FIRST_CONTACT);
        publishMoment(200, GameMomentType.BATTLE_STARTED);
        publishMoment(300, GameMomentType.BATTLE_ENDED);
        publishMoment(400, GameMomentType.SUPPLY_BLOCK);
        accumulator.tick(FRAME_672);  // first emit

        // When — second batch accumulates but <672 frames since last emit
        publishMoment(800, GameMomentType.ARMY_SHIFT);
        publishMoment(900, GameMomentType.POSTURE_CHANGE);
        publishMoment(1000, GameMomentType.BATTLE_STARTED);
        publishMoment(1100, GameMomentType.BATTLE_ENDED);

        // Tick at 1200 (only 528 frames since last emit at 672)
        Map<String, Object> result = accumulator.tick(1200);

        // Then — blocked by time floor
        assertThat(result).isEmpty();
    }

    @Test
    void minimumTimeFloor_subsequentEmit_allowed() {
        // Given — first emit at frame 672
        publishMoment(100, GameMomentType.FIRST_CONTACT);
        publishMoment(200, GameMomentType.BATTLE_STARTED);
        publishMoment(300, GameMomentType.BATTLE_ENDED);
        publishMoment(400, GameMomentType.SUPPLY_BLOCK);
        accumulator.tick(FRAME_672);  // first emit

        // When — second batch accumulates AND >=672 frames since last emit
        publishMoment(800, GameMomentType.ARMY_SHIFT);
        publishMoment(900, GameMomentType.POSTURE_CHANGE);
        publishMoment(1000, GameMomentType.BATTLE_STARTED);
        publishMoment(1100, GameMomentType.BATTLE_ENDED);

        // Tick at 1344 (1344 - 672 = 672 frames since last emit)
        Map<String, Object> result = accumulator.tick(1344);

        // Then — emit allowed
        assertThat(result).containsKey(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER);
    }

    @Test
    void gameStarted_clearsAccumulator() {
        // Given — moments accumulated
        publishMoment(100, GameMomentType.FIRST_CONTACT);
        publishMoment(200, GameMomentType.BATTLE_STARTED);

        // When
        accumulator.onGameStarted(new GameStarted());

        // Then — tick returns empty (accumulator cleared)
        Map<String, Object> result = accumulator.tick(1000);
        assertThat(result).isEmpty();
    }

    @Test
    void triggerMapContainsBatchAndContext() {
        // Given — moments + context
        publishMoment(100, GameMomentType.FIRST_CONTACT);
        publishMoment(200, GameMomentType.BATTLE_STARTED);
        publishMoment(300, GameMomentType.BATTLE_ENDED);
        publishMoment(400, GameMomentType.SUPPLY_BLOCK);

        phaseBus.publish(new LevelEvent<>(
            new TacticalPosture("Late-game", 300L, "Tech race"), 300L, new EventLevel("phase", 3)));
        arcBus.publish(new LevelEvent<>(
            new GameArc("Narrative arc content", 600L), 600L, new EventLevel("arc", 4)));

        // When
        Map<String, Object> result = accumulator.tick(FRAME_672);

        // Then — trigger map contains serialized batch + context snapshot
        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = (Map<String, Object>) result.get(
            QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER);

        assertThat(trigger).containsKey("batch");
        assertThat(trigger).containsKey("context");

        @SuppressWarnings("unchecked")
        Map<String, String> context = (Map<String, String>) trigger.get("context");
        assertThat(context).containsEntry("phase", "Late-game");
        assertThat(context).containsEntry("phase_rationale", "Tech race");
        assertThat(context).containsEntry("arc_narrative", "Narrative arc content");
    }

    @Test
    void noMoments_tickReturnsEmpty() {
        // When — tick with empty accumulator
        Map<String, Object> result = accumulator.tick(1000);

        // Then
        assertThat(result).isEmpty();
    }

    private void publishMoment(long frame, GameMomentType type) {
        var moment = new GameMoment(type, frame, Map.of());
        momentBus.publish(new LevelEvent<>(moment, frame, LEVEL_2));
    }
}
