package io.quarkmind.domain;

public interface PhaseResolver {

    GamePhase resolve(double gameTimeMinutes);
}
