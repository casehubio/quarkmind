package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.offset;

class AnchorInterpolatorTest {

    @Test
    void emptyAnchorList_rejected() {
        assertThatThrownBy(() -> new AnchorInterpolator(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one");
    }

    @Test
    void duplicateFrames_rejected() {
        assertThatThrownBy(() -> new AnchorInterpolator(List.of(
                anchor(100, 0.30, 0.35, 0.20, 0.05, 0.10),
                anchor(100, 0.20, 0.20, 0.20, 0.20, 0.20))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictly ascending");
    }

    @Test
    void descendingFrames_rejected() {
        assertThatThrownBy(() -> new AnchorInterpolator(List.of(
                anchor(200, 0.30, 0.35, 0.20, 0.05, 0.10),
                anchor(100, 0.20, 0.20, 0.20, 0.20, 0.20))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictly ascending");
    }

    @Test
    void singleAnchor_alwaysReturnsSameWeights() {
        var interp = new AnchorInterpolator(List.of(
            anchor(0, 0.30, 0.35, 0.20, 0.05, 0.10)));
        DominanceWeights w = interp.interpolate(5000);
        assertThat(w.economy()).isCloseTo(0.30, offset(0.001));
        assertThat(w.army()).isCloseTo(0.35, offset(0.001));
        assertThat(w.tech()).isCloseTo(0.20, offset(0.001));
        assertThat(w.bases()).isCloseTo(0.05, offset(0.001));
        assertThat(w.mapControl()).isCloseTo(0.10, offset(0.001));
    }

    @Test
    void beforeFirstAnchor_returnsFirstWeights() {
        var interp = new AnchorInterpolator(List.of(
            anchor(1000, 0.40, 0.20, 0.20, 0.05, 0.15),
            anchor(2000, 0.20, 0.40, 0.20, 0.05, 0.15)));
        DominanceWeights w = interp.interpolate(500);
        assertThat(w.economy()).isCloseTo(0.40, offset(0.001));
        assertThat(w.army()).isCloseTo(0.20, offset(0.001));
    }

    @Test
    void afterLastAnchor_returnsLastWeights() {
        var interp = new AnchorInterpolator(List.of(
            anchor(1000, 0.40, 0.20, 0.20, 0.05, 0.15),
            anchor(2000, 0.20, 0.40, 0.20, 0.05, 0.15)));
        DominanceWeights w = interp.interpolate(5000);
        assertThat(w.economy()).isCloseTo(0.20, offset(0.001));
        assertThat(w.army()).isCloseTo(0.40, offset(0.001));
    }

    @Test
    void atExactAnchorFrame_returnsAnchorWeights() {
        var interp = new AnchorInterpolator(List.of(
            anchor(1000, 0.40, 0.20, 0.20, 0.05, 0.15),
            anchor(2000, 0.20, 0.40, 0.20, 0.05, 0.15)));
        DominanceWeights w = interp.interpolate(1000);
        assertThat(w.economy()).isCloseTo(0.40, offset(0.001));
    }

    @Test
    void midpointBetweenAnchors_interpolatesLinearly() {
        var interp = new AnchorInterpolator(List.of(
            anchor(0, 0.40, 0.20, 0.20, 0.05, 0.15),
            anchor(10000, 0.20, 0.40, 0.20, 0.05, 0.15)));
        DominanceWeights w = interp.interpolate(5000);
        assertThat(w.economy()).isCloseTo(0.30, offset(0.001));
        assertThat(w.army()).isCloseTo(0.30, offset(0.001));
        assertThat(w.tech()).isCloseTo(0.20, offset(0.001));
        assertThat(w.bases()).isCloseTo(0.05, offset(0.001));
        assertThat(w.mapControl()).isCloseTo(0.15, offset(0.001));
    }

    @Test
    void quarterPointBetweenAnchors_interpolatesLinearly() {
        var interp = new AnchorInterpolator(List.of(
            anchor(0, 0.40, 0.20, 0.20, 0.05, 0.15),
            anchor(10000, 0.20, 0.40, 0.20, 0.05, 0.15)));
        DominanceWeights w = interp.interpolate(2500);
        assertThat(w.economy()).isCloseTo(0.35, offset(0.001));
        assertThat(w.army()).isCloseTo(0.25, offset(0.001));
    }

    @Test
    void threeAnchors_interpolatesBetweenCorrectPair() {
        var interp = new AnchorInterpolator(List.of(
            anchor(0, 0.40, 0.20, 0.20, 0.05, 0.15),
            anchor(8064, 0.25, 0.35, 0.20, 0.05, 0.15),
            anchor(16128, 0.10, 0.50, 0.15, 0.10, 0.15)));
        DominanceWeights w = interp.interpolate(12096);
        assertThat(w.economy()).isCloseTo(0.175, offset(0.001));
        assertThat(w.army()).isCloseTo(0.425, offset(0.001));
    }

    @Test
    void interpolatedWeights_sumToOne() {
        var interp = new AnchorInterpolator(List.of(
            anchor(0, 0.40, 0.20, 0.20, 0.05, 0.15),
            anchor(10000, 0.10, 0.50, 0.15, 0.10, 0.15)));
        DominanceWeights w = interp.interpolate(3333);
        double sum = w.economy() + w.army() + w.tech() + w.bases() + w.mapControl();
        assertThat(sum).isCloseTo(1.0, offset(0.001));
    }

    static MilestoneConfig.Dominance.WeightAnchor anchor(
            long frame, double economy, double army, double tech, double bases, double mapControl) {
        return new MilestoneConfig.Dominance.WeightAnchor() {
            @Override public long frame() { return frame; }
            @Override public double economyWeight() { return economy; }
            @Override public double armyWeight() { return army; }
            @Override public double techWeight() { return tech; }
            @Override public double basesWeight() { return bases; }
            @Override public double mapControlWeight() { return mapControl; }
        };
    }
}
