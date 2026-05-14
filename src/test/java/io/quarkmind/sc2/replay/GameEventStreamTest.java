package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameEventStreamTest {

    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    List<Event> events;

    @BeforeAll
    void loadEvents() {
        events = GameEventStream.events(REPLAY);
    }

    @Test
    void returnsNonEmptyEventList() {
        assertThat(events).isNotEmpty();
    }

    @Test
    void containsCmdEvents() {
        assertThat(events).anyMatch(e -> e instanceof CmdEvent);
    }

    @Test
    void containsSelectionDeltaEvents() {
        assertThat(events).anyMatch(e -> e instanceof SelectionDeltaEvent);
    }

    @Test
    void allEventsHaveNonNegativeLoops() {
        assertThat(events).allMatch(e -> e.getLoop() >= 0);
    }

    @Test
    void throwsForMissingFile() {
        assertThatThrownBy(() -> GameEventStream.events(Path.of("nonexistent.SC2Replay")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonexistent.SC2Replay");
    }

    @Test
    void decodeTagRoundTrip() {
        // index=5, recycle=3 → raw = (5 << 18) | 3 = 1310723
        int rawTag = (5 << 18) | 3;
        assertThat(GameEventStream.decodeTag(rawTag)).isEqualTo("r-5-3");
    }

    @Test
    void decodeTagZeroValues() {
        assertThat(GameEventStream.decodeTag(0)).isEqualTo("r-0-0");
    }
}
