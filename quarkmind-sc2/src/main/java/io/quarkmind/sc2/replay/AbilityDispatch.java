package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;

import java.util.List;

@FunctionalInterface
public interface AbilityDispatch {
    List<ReplayCommand> dispatch(int idx, CmdEvent event, long loop, Race race, int unitLink);
}
