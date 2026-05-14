package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameEventStreamTest {

    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    @Test
    void returnsNonEmptyEventList() {
        List<Event> events = GameEventStream.events(REPLAY);
        assertThat(events).isNotEmpty();
    }

    @Test
    void containsCmdEvents() {
        List<Event> events = GameEventStream.events(REPLAY);
        assertThat(events).anyMatch(e -> e instanceof CmdEvent);
    }

    @Test
    void containsSelectionDeltaEvents() {
        List<Event> events = GameEventStream.events(REPLAY);
        assertThat(events).anyMatch(e -> e instanceof SelectionDeltaEvent);
    }

    @Test
    void allEventsHaveNonNegativeLoops() {
        List<Event> events = GameEventStream.events(REPLAY);
        assertThat(events).allMatch(e -> e.getLoop() >= 0);
    }

    @Test
    void throwsForMissingFile() {
        assertThatThrownBy(() -> GameEventStream.events(Path.of("nonexistent.SC2Replay")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
