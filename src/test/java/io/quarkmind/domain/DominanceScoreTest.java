package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DominanceScoreTest {

    @Test
    void factorsAreImmutable() {
        var mutable = new java.util.HashMap<>(Map.of("economy", 0.5));
        var score = new DominanceScore(0.5, mutable);
        assertThatThrownBy(() -> score.factors().put("army", 0.3))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void overallAndFactorsPreserved() {
        var score = new DominanceScore(0.7, Map.of("economy", 0.3, "army", 0.4));
        assertThat(score.overall()).isEqualTo(0.7);
        assertThat(score.factors()).containsEntry("economy", 0.3);
        assertThat(score.factors()).containsEntry("army", 0.4);
    }
}
