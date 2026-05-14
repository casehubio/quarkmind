package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public final class GameEventStream {

    private GameEventStream() {}

    public static List<Event> events(Path replayPath) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.GAME_EVENTS));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse GAME_EVENTS from: " + replayPath, e);
        }
        if (replay == null || replay.gameEvents == null) {
            throw new IllegalArgumentException("No game events in replay: " + replayPath);
        }
        return Arrays.asList(replay.gameEvents.getEvents());
    }

    /** Decode raw SC2 unit tag integer to "r-{index}-{recycle}" format. */
    static String decodeTag(int rawTag) {
        return "r-" + (rawTag >> 18) + "-" + (rawTag & 0x3FFFF);
    }
}
