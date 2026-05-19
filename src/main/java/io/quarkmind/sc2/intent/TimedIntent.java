package io.quarkmind.sc2.intent;

/** An Intent extracted from replay GAME_EVENTS, tagged with its raw SC2 game loop. */
public record TimedIntent(long loop, Intent intent) {}
