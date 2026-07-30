package io.quarkmind.agent;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PhaseResolver;

public class TimeBasedPhaseResolver implements PhaseResolver {

    static final double EARLY_END = 5.0;
    static final double MID_END   = 12.0;

    @Override
    public GamePhase resolve(GameState gameState) {
        double gameTimeMinutes = gameState.gameTimeMinutes();
        if (gameTimeMinutes < EARLY_END) {return GamePhase.EARLY;}
        if (gameTimeMinutes < MID_END) {return GamePhase.MID;}
        return GamePhase.LATE;
    }
}
