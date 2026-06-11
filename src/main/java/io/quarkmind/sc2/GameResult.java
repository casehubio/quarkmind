package io.quarkmind.sc2;

/** Outcome of a completed SC2 game, as observed from the SC2 API {@code playerResult}. */
public enum GameResult {
    WIN,
    LOSS,
    TIE,
    /** Game ended without a determinable result (interrupted, crashed, or mid-game stop). */
    UNKNOWN
}
