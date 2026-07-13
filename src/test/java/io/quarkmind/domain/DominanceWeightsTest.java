package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DominanceWeightsTest {

    @Test
    void validWeights_accepted() {
        var w = new DominanceWeights(0.30, 0.35, 0.20, 0.15);
        assertThat(w.economy()).isEqualTo(0.30);
        assertThat(w.army()).isEqualTo(0.35);
        assertThat(w.tech()).isEqualTo(0.20);
        assertThat(w.bases()).isEqualTo(0.15);
    }

    @Test
    void weightsSumTooHigh_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.50, 0.50, 0.20, 0.15))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sum to 1.0");
    }

    @Test
    void weightsSumTooLow_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.10, 0.10, 0.10, 0.10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sum to 1.0");
    }

    @Test
    void equalQuarters_accepted() {
        var w = new DominanceWeights(0.25, 0.25, 0.25, 0.25);
        assertThat(w.economy()).isEqualTo(0.25);
    }

    @Test
    void allZeros_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.0, 0.0, 0.0, 0.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withinTolerance_accepted() {
        assertThatCode(() -> new DominanceWeights(0.2499, 0.2501, 0.25, 0.25))
            .doesNotThrowAnyException();
    }

    @Test
    void beyondTolerance_rejected() {
        assertThatThrownBy(() -> new DominanceWeights(0.30, 0.35, 0.20, 0.16))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
