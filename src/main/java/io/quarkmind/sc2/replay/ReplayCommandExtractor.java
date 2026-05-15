package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReplayCommandExtractor {

    private ReplayCommandExtractor() {}

    /**
     * Parses GAME_EVENTS for the given player and returns movement orders
     * and train intents in loop-ascending order.
     *
     * @param replayPath  path to the .SC2Replay file
     * @param playerId    1-indexed player ID (matches starcraft.replay.player config)
     */
    public static ReplayCommandStream extract(Path replayPath, int playerId) {
        List<Event> events = GameEventStream.events(replayPath);
        AbilityMapping mapping = new AbilityMapping(playerId);
        List<UnitOrder>   orders  = new ArrayList<>();
        List<TimedIntent> intents = new ArrayList<>();

        for (Event raw : events) {
            if (raw instanceof SelectionDeltaEvent sel) {
                mapping.onSelection(sel);
            } else if (raw instanceof CmdEvent cmd) {
                for (ReplayCommand rc : mapping.process(cmd)) {
                    switch (rc) {
                        case ReplayCommand.Movement      m -> orders.add(m.order());
                        case ReplayCommand.IntentCommand i -> intents.add(i.intent());
                    }
                }
            }
        }

        return new ReplayCommandStream(
            Collections.unmodifiableList(orders),
            Collections.unmodifiableList(intents));
    }
}
