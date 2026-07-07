package io.quarkmind.plugin.commentary;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkmind.plugin.summarisation.GameArc;
import io.quarkmind.plugin.summarisation.GamePhase;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NarrativeContextHolder}.
 *
 * <p>Tests without CDI — constructs holder directly, publishes events to buses,
 * and asserts state changes.
 *
 * <p>Refs #181 Task 6
 */
class NarrativeContextHolderTest {

    private static final EventLevel LEVEL_3 = new EventLevel("phase", 3);
    private static final EventLevel LEVEL_4 = new EventLevel("arc", 4);

    private EventStreamBus<GamePhase> phaseBus;
    private EventStreamBus<GameArc> arcBus;
    private NarrativeContextHolder holder;

    @BeforeEach
    void setUp() {
        phaseBus = new EventStreamBus<>();
        arcBus = new EventStreamBus<>();
        holder = new NarrativeContextHolder(phaseBus, arcBus);
        holder.init();
    }

    @Test
    void publishesPhase_updatesLatestPhase() {
        var phase = new GamePhase("Opening", 100L, "Economy priority");
        phaseBus.publish(new LevelEvent<>(phase, 100L, LEVEL_3));

        assertThat(holder.latestPhase()).isEqualTo(phase);
    }

    @Test
    void publishesArc_updatesLatestArc() {
        var arc = new GameArc("Bot establishes macro advantage", 500L);
        arcBus.publish(new LevelEvent<>(arc, 500L, LEVEL_4));

        assertThat(holder.latestArc()).isEqualTo(arc);
    }

    @Test
    void gameStarted_clearsBothFields() {
        // Given — context populated
        phaseBus.publish(new LevelEvent<>(
            new GamePhase("Mid-game", 200L, "Skirmish"), 200L, LEVEL_3));
        arcBus.publish(new LevelEvent<>(
            new GameArc("Narrative arc", 400L), 400L, LEVEL_4));

        // When
        holder.onGameStarted(new GameStarted());

        // Then
        assertThat(holder.latestPhase()).isNull();
        assertThat(holder.latestArc()).isNull();
    }

    @Test
    void snapshot_returnsMapWithPhaseAndArcData() {
        // Given
        var phase = new GamePhase("Late-game", 300L, "Tech race");
        var arc = new GameArc("Strategic pivot to air", 600L);
        phaseBus.publish(new LevelEvent<>(phase, 300L, LEVEL_3));
        arcBus.publish(new LevelEvent<>(arc, 600L, LEVEL_4));

        // When
        Map<String, String> snapshot = holder.snapshot();

        // Then
        assertThat(snapshot).containsEntry("phase", "Late-game");
        assertThat(snapshot).containsEntry("phase_rationale", "Tech race");
        assertThat(snapshot).containsEntry("arc_narrative", "Strategic pivot to air");
    }

    @Test
    void snapshot_nullPhase_returnsPartialMap() {
        // Given — only arc populated
        var arc = new GameArc("Early aggression", 100L);
        arcBus.publish(new LevelEvent<>(arc, 100L, LEVEL_4));

        // When
        Map<String, String> snapshot = holder.snapshot();

        // Then
        assertThat(snapshot).doesNotContainKeys("phase", "phase_rationale");
        assertThat(snapshot).containsEntry("arc_narrative", "Early aggression");
    }

    @Test
    void snapshot_nullArc_returnsPartialMap() {
        // Given — only phase populated
        var phase = new GamePhase("Opening", 50L, "Scout rush");
        phaseBus.publish(new LevelEvent<>(phase, 50L, LEVEL_3));

        // When
        Map<String, String> snapshot = holder.snapshot();

        // Then
        assertThat(snapshot).containsEntry("phase", "Opening");
        assertThat(snapshot).containsEntry("phase_rationale", "Scout rush");
        assertThat(snapshot).doesNotContainKey("arc_narrative");
    }

    @Test
    void snapshot_bothNull_returnsEmptyMap() {
        Map<String, String> snapshot = holder.snapshot();
        assertThat(snapshot).isEmpty();
    }
}
