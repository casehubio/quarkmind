package io.quarkmind.agent;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TimeBasedPhaseResolverTest {

    private final TimeBasedPhaseResolver resolver = new TimeBasedPhaseResolver();

    @ParameterizedTest
    @CsvSource({
            "0.0, EARLY", "2.5, EARLY", "4.99, EARLY",
            "5.0, MID", "8.0, MID", "11.99, MID",
            "12.0, LATE", "20.0, LATE"
    })
    void resolve_mapsTimeToPhase(double minutes, GamePhase expected) {
        long frame = Math.round(minutes * 60 * SC2Data.GAME_LOOPS_PER_SECOND);
        GameState state = new GameState(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), frame, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        assertThat(resolver.resolve(state)).isEqualTo(expected);
    }
}
