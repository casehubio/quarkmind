package io.quarkmind.plugin.scouting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TemporalWindowAccumulatorTest {

    @Test
    void emptyAccumulator_returnsZeroPaddedWindows() {
        var acc = new TemporalWindowAccumulator();
        var windows = acc.getWindowedFeatures();
        assertThat(windows).hasSize(10);
        for (float[] w : windows) {
            assertThat(w).hasSize(269);
            assertThat(w).containsOnly(0.0f);
        }
    }

    @Test
    void twoMinutesOfTicks_populatesFourWindows() {
        var acc = new TemporalWindowAccumulator();
        // 2 minutes = 120 seconds = 240 ticks at ~0.5s per tick
        // 4 windows of 60 ticks each
        for (int i = 0; i < 240; i++) {
            var player = new float[134];
            player[0] = 1.0f;
            var opponent = new float[134];
            acc.addSnapshot(new WindowSnapshot(player, opponent, 0.5f));
        }
        var windows = acc.getWindowedFeatures();
        assertThat(windows).hasSize(10);
        assertThat(windows.get(0)[0]).isGreaterThan(0);
        assertThat(windows.get(3)[0]).isGreaterThan(0);
        assertThat(windows.get(4)).containsOnly(0.0f);
        assertThat(windows.get(9)).containsOnly(0.0f);
    }

    @Test
    void scoutingMask_appliedToOpponentFeatures() {
        var acc = new TemporalWindowAccumulator();
        for (int i = 0; i < 60; i++) {
            var player = new float[134];
            var opponent = new float[134];
            opponent[53] = 10.0f;
            acc.addSnapshot(new WindowSnapshot(player, opponent, 0.3f));
        }
        var windows = acc.getWindowedFeatures();
        // Opponent marines at index 134+53 = 187, masked by 0.3
        assertThat(windows.get(0)[187]).isCloseTo(3.0f, within(0.1f));
        // has_vision flag at index 268
        assertThat(windows.get(0)[268]).isEqualTo(1.0f);
    }

    @Test
    void reset_clearsAllState() {
        var acc = new TemporalWindowAccumulator();
        acc.addSnapshot(new WindowSnapshot(new float[134], new float[134], 0.5f));
        acc.reset();
        var windows = acc.getWindowedFeatures();
        assertThat(windows.get(0)).containsOnly(0.0f);
    }
}
