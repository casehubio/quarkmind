package io.quarkmind.sc2.replay;

import java.util.List;

/** Full extraction result from a replay's GAME_EVENTS for one player. */
public record ReplayCommandStream(
    List<UnitOrder>   movementOrders,
    List<TimedIntent> intents) {}
