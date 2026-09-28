package io.quarkmind.sc2.replay;

import io.quarkmind.domain.Point2d;
import io.quarkmind.sc2.intent.TimedIntent;

public sealed interface ReplayCommand permits
                                      ReplayCommand.Movement, ReplayCommand.IntentCommand,
                                      ReplayCommand.BuildCommand, ReplayCommand.UpgradeCommand,
                                      ReplayCommand.MorphCommand, ReplayCommand.CancelCommand {
    record Movement(UnitOrder order) implements ReplayCommand {}

    record IntentCommand(TimedIntent intent) implements ReplayCommand {}

    record BuildCommand(long loop, String buildingName, Point2d position) implements ReplayCommand {}

    record UpgradeCommand(long loop, String upgradeName) implements ReplayCommand {}

    record MorphCommand(long loop, String sourceName, String targetName) implements ReplayCommand {}

    record CancelCommand(long loop, String unitTag) implements ReplayCommand {}
}
