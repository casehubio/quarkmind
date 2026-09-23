package io.quarkmind.plugin.scouting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TemporalWindowAccumulatorTest {

    @Test
    void emptyAccumulator_returnsZeroPaddedWindows() {
        var acc     = new TemporalWindowAccumulator();
        var windows = acc.getWindowedFeatures();
        assertThat(windows).hasSize(10);
        for (float[] w : windows) {
            assertThat(w).hasSize(FeatureIndexMaps.FEATURES_PER_WINDOW);
            assertThat(w).containsOnly(0.0f);
        }
    }

    @Test
    void twoMinutesOfTicks_populatesFourWindows() {
        var acc = new TemporalWindowAccumulator();
        int N   = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
        for (int i = 0; i < 240; i++) {
            var player = new float[N];
            player[0] = 1.0f;
            var opponent = new float[N];
            acc.addSnapshot(new WindowSnapshot(player, opponent, 0.5f));
        }
        var windows = acc.getWindowedFeatures();
        assertThat(windows).hasSize(10);
        assertThat(windows.get(0)).hasSize(FeatureIndexMaps.FEATURES_PER_WINDOW);
        assertThat(windows.get(0)[0]).isGreaterThan(0);
        assertThat(windows.get(3)[0]).isGreaterThan(0);
    }

    @Test
    void scoutingMask_appliedToOpponentFeatures() {
        var acc = new TemporalWindowAccumulator();
        int N   = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
        int NP  = FeatureIndexMaps.N_FEATURES_PER_PLAYER;
        for (int i = 0; i < 60; i++) {
            var player   = new float[N];
            var opponent = new float[N];
            opponent[53] = 10.0f; // marine count (composition, index < 134)
            acc.addSnapshot(new WindowSnapshot(player, opponent, 0.3f));
        }
        var windows = acc.getWindowedFeatures();
        // Opponent marines at NP + 53, masked by continuous 0.3
        assertThat(windows.get(0)[NP + 53]).isCloseTo(3.0f, within(0.1f));
        // has_vision flag at last index
        assertThat(windows.get(0)[FeatureIndexMaps.HAS_VISION_INDEX]).isEqualTo(1.0f);
    }

    @Test
    void reset_clearsAllState() {
        var acc = new TemporalWindowAccumulator();
        int N   = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
        acc.addSnapshot(new WindowSnapshot(new float[N], new float[N], 0.5f));
        acc.reset();
        var windows = acc.getWindowedFeatures();
        assertThat(windows.get(0)).containsOnly(0.0f);
    }

    @Test
    void opponentSpatialFeatures_notScaledByContinuousVisibility() {
        var acc        = new TemporalWindowAccumulator();
        int N          = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
        int NP         = FeatureIndexMaps.N_FEATURES_PER_PLAYER;
        int spatialOff = FeatureIndexMaps.SPATIAL_OFFSET;
        for (int i = 0; i < 60; i++) {
            var player   = new float[N];
            var opponent = new float[N];
            opponent[spatialOff] = 0.4f; // centroid_x (spatial feature)
            opponent[0]          = 2.0f; // building count (composition feature)
            acc.addSnapshot(new WindowSnapshot(player, opponent, 0.3f));
        }
        var windows = acc.getWindowedFeatures();
        // Composition feature (index 0): should be scaled by 0.3 → 2.0 * 0.3 = 0.6
        assertThat(windows.get(0)[NP + 0]).isCloseTo(0.6f, within(0.05f));
        // Spatial feature: binary visibility (visibility > 0 → 1.0), so full value 0.4
        assertThat(windows.get(0)[NP + spatialOff]).isCloseTo(0.4f, within(0.05f));
    }

    @Test
    void deltas_window0_allZeros() {
        var acc = new TemporalWindowAccumulator();
        int N   = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
        for (int i = 0; i < 60; i++) {
            var player = new float[N];
            player[FeatureIndexMaps.N_BUILDINGS] = 5.0f; // marines
            acc.addSnapshot(new WindowSnapshot(player, new float[N], 0.0f));
        }
        var windows  = acc.getWindowedFeatures();
        int deltaOff = FeatureIndexMaps.DELTA_OFFSET;
        for (int d = 0; d < FeatureIndexMaps.N_DELTAS; d++) {
            assertThat(windows.get(0)[deltaOff + d]).as("player delta[%d] in window 0", d).isEqualTo(0f);
        }
    }

    @Test
    void deltas_window1_reflectsChanges() {
        var acc       = new TemporalWindowAccumulator();
        int N         = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
        int marineIdx = FeatureIndexMaps.N_BUILDINGS + 1; // Marine unit index = 1
        // Window 0: 2 marines
        for (int i = 0; i < 60; i++) {
            var player = new float[N];
            player[marineIdx] = 2.0f;
            acc.addSnapshot(new WindowSnapshot(player, new float[N], 0.0f));
        }
        // Window 1: 6 marines
        for (int i = 0; i < 60; i++) {
            var player = new float[N];
            player[marineIdx] = 6.0f;
            acc.addSnapshot(new WindowSnapshot(player, new float[N], 0.0f));
        }
        var windows  = acc.getWindowedFeatures();
        int deltaOff = FeatureIndexMaps.DELTA_OFFSET;
        // delta_army_supply: marine supply cost = 1, so delta = (6-2)*1 = 4
        assertThat(windows.get(1)[deltaOff]).as("delta_army_supply").isCloseTo(4.0f, within(0.1f));
    }

    @Test
    void armyGap_computedFromAveragedCentroids() {
        var acc   = new TemporalWindowAccumulator();
        int N     = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
        int cxOff = FeatureIndexMaps.SPATIAL_OFFSET;
        int cyOff = FeatureIndexMaps.SPATIAL_OFFSET + 1;
        for (int i = 0; i < 60; i++) {
            var player = new float[N];
            player[cxOff] = 0.2f; // player centroid at (0.2, 0.2)
            player[cyOff] = 0.2f;
            var opponent = new float[N];
            opponent[cxOff] = 0.8f; // opponent centroid at (0.8, 0.8)
            opponent[cyOff] = 0.8f;
            acc.addSnapshot(new WindowSnapshot(player, opponent, 1.0f));
        }
        var windows = acc.getWindowedFeatures();
        int gapIdx  = FeatureIndexMaps.ARMY_GAP_INDEX;
        // gap = sqrt((0.8-0.2)^2 + (0.8-0.2)^2) = sqrt(0.72) ≈ 0.849
        assertThat(windows.get(0)[gapIdx]).isCloseTo(0.849f, within(0.01f));
    }
}
