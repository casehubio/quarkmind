package io.quarkmind.sc2;

/**
 * CDI event fired when a game ends. Carries the outcome observed from the SC2 API.
 * Observers must use {@code @Observes} (synchronous) — see protocol
 * {@code game-lifecycle-observer-synchrony.md}.
 */
public record GameStopped(GameResult result) {}
