package io.quarkmind.sc2.replay;

import io.quarkmind.sc2.intent.TimedIntent;

/** Discriminated union of what a single CmdEvent produces. */
public sealed interface ReplayCommand permits ReplayCommand.Movement, ReplayCommand.IntentCommand {
    record Movement(UnitOrder order)          implements ReplayCommand {}
    record IntentCommand(TimedIntent intent)  implements ReplayCommand {}
}
