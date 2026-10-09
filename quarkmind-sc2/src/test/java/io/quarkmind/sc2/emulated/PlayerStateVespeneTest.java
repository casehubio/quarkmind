package io.quarkmind.sc2.emulated;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerStateVespeneTest {

    @Test
    void addVespene_accumulatesFromZero() {
        var state = new PlayerState();
        state.addVespene(0.622);
        state.addVespene(0.622);
        state.addVespene(0.622);
        assertThat(state.vespene()).isEqualTo(1);
    }

    @Test
    void addVespene_accumulatesPrecisely() {
        var state = new PlayerState();
        for (int i = 0; i < 100; i++) {
            state.addVespene(0.622);
        }
        assertThat(state.vespene()).isEqualTo(62);
    }

    @Test
    void setVespene_overwritesAccumulated() {
        var state = new PlayerState();
        state.addVespene(50.0);
        state.setVespene(100);
        assertThat(state.vespene()).isEqualTo(100);
    }

    @Test
    void deductVespene_worksWithDoubleField() {
        var state = new PlayerState();
        state.setVespene(100);
        state.deductVespene(25);
        assertThat(state.vespene()).isEqualTo(75);
    }
}
