package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import io.quarkmind.sc2.intent.TimedIntent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public final class ReplayCommandExtractor {

    private ReplayCommandExtractor() {}

    public static ReplayCommandStream extract(Path replayPath, int playerId) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.GAME_EVENTS));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse replay: " + replayPath, e);
        }
        if (replay == null || replay.gameEvents == null) {
            throw new IllegalArgumentException("No game events in replay: " + replayPath);
        }
        List<Event> events = List.of(replay.gameEvents.getEvents());

        AbilityProfile profile = AbilityProfile.resolve(
                replay.header != null && replay.header.baseBuild != null
                        ? replay.header.baseBuild : 75689);
        AbilityMapping mapping = new AbilityMapping(playerId, false, null, profile);
        List<UnitOrder>   orders  = new ArrayList<>();
        List<TimedIntent> intents = new ArrayList<>();

        for (Event raw : events) {
            if (raw instanceof SelectionDeltaEvent sel) {
                mapping.onSelection(sel);
            } else if (raw instanceof CmdEvent cmd) {
                for (ReplayCommand rc : mapping.process(cmd)) {
                    switch (rc) {
                        case ReplayCommand.Movement      m -> orders.add(m.order());
                        case ReplayCommand.IntentCommand  i -> intents.add(i.intent());
                        case ReplayCommand.BuildCommand   ignored -> {}
                        case ReplayCommand.UpgradeCommand ignored -> {}
                        case ReplayCommand.MorphCommand   ignored -> {}
                        case ReplayCommand.CancelCommand  ignored -> {}
                    }
                }
            }
        }

        return new ReplayCommandStream(
            Collections.unmodifiableList(orders),
            Collections.unmodifiableList(intents));
    }
}
