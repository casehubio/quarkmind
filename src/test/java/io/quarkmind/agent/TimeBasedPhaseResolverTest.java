package io.quarkmind.agent;

import io.quarkmind.domain.GamePhase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        assertThat(resolver.resolve(minutes)).isEqualTo(expected);
    }
}
