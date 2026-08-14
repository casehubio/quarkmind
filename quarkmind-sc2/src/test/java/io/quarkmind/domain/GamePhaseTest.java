package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GamePhaseTest {

    @Test
    void threePhases() {
        assertThat(GamePhase.values()).containsExactly(
            GamePhase.EARLY, GamePhase.MID, GamePhase.LATE);
    }
}
