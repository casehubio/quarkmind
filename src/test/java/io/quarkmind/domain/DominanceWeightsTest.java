package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DominanceWeightsTest {

    @Test
    void validWeights_accepted() {
        var w = new DominanceWeights(0.30, 0.35, 0.20, 0.05, 0.10);
        assertThat(w.economy()).isEqualTo(0.30);
        assertThat(w.army()).isEqualTo(0.35);
        assertThat(w.tech()).isEqualTo(0.20);
        assertThat(w.bases()).isEqualTo(0.05);
        assertThat(w.mapControl()).isEqualTo(0.10);
    }

    @Test
    void weightsSumTooHigh_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.50, 0.50, 0.20, 0.15, 0.10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sum to 1.0");
    }

    @Test
    void weightsSumTooLow_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.10, 0.10, 0.10, 0.10, 0.10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sum to 1.0");
    }

    @Test
    void equalFifths_accepted() {
        var w = new DominanceWeights(0.20, 0.20, 0.20, 0.20, 0.20);
        assertThat(w.economy()).isEqualTo(0.20);
    }

    @Test
    void allZeros_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.0, 0.0, 0.0, 0.0, 0.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withinTolerance_accepted() {
        assertThatCode(() -> new DominanceWeights(0.1999, 0.2001, 0.20, 0.20, 0.20))
            .doesNotThrowAnyException();
    }

    @Test
    void beyondTolerance_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.30, 0.35, 0.20, 0.06, 0.10))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
