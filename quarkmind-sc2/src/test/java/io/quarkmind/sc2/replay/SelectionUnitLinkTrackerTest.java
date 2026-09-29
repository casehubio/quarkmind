package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionUnitLinkTrackerTest {

    private static final int USER_ID = 0;

    @Test
    void duplicateTagsAreNotAccumulated() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 3}},
            new Integer[]{101, 102, 103});
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(3);

        tracker.applyDelta(null, null,
            new int[][]{{70, 3}},
            new Integer[]{101, 102, 103});
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(3);
    }

    @Test
    void incrementalAddWithNewTags() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 2}},
            new Integer[]{101, 102});
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(2);

        tracker.applyDelta(null, null,
            new int[][]{{70, 1}},
            new Integer[]{103});
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(3);
    }

    @Test
    void zeroIndicesKeepsOnlyListedPositions() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 2}, {75, 1}},
            new Integer[]{101, 102, 201});

        // Keep indices 0 and 1 (both Marines), drop index 2 (Tank)
        tracker.applyDelta("ZeroIndices", new Integer[]{0, 1},
            null, null);
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(2);
        assertThat(tracker.countMatching(Set.of(75))).isEqualTo(0);
    }

    @Test
    void oneIndicesRemovesListedPositions() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 3}},
            new Integer[]{101, 102, 103});

        tracker.applyDelta("OneIndices", new Integer[]{1},
            null, null);
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(2);
    }

    @Test
    void maskRemovesMarkedPositions() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 3}},
            new Integer[]{101, 102, 103});

        // BitArray(count, byte[]) — bit 1 set = 0x02
        var bitArray = new hu.belicza.andras.util.type.BitArray(3, new byte[]{0x02});
        tracker.applyDelta("Mask", bitArray,
            null, null);
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(2);
    }

    @Test
    void nullUnitTagsFallsBackToSyntheticTags() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 2}}, null);
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(2);

        tracker.applyDelta(null, null,
            new int[][]{{70, 2}}, null);
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(4);
    }

    @Test
    void mixedSubgroupsZipCorrectly() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 2}, {75, 1}},
            new Integer[]{101, 102, 201});
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(2);
        assertThat(tracker.countMatching(Set.of(75))).isEqualTo(1);
        assertThat(tracker.unitLinksSnapshot()).containsExactly(70, 70, 75);
    }

    @Test
    void unknownVariantClearsAll() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 3}},
            new Integer[]{101, 102, 103});

        tracker.applyDelta("SomeNewVariant", new Object(),
            null, null);
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(0);
    }

    @Test
    void removeAndAddInSameEvent() {
        var tracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(USER_ID);

        tracker.applyDelta(null, null,
            new int[][]{{70, 3}},
            new Integer[]{101, 102, 103});

        tracker.applyDelta("OneIndices", new Integer[]{0},
            new int[][]{{75, 1}},
            new Integer[]{201});
        assertThat(tracker.countMatching(Set.of(70))).isEqualTo(2);
        assertThat(tracker.countMatching(Set.of(75))).isEqualTo(1);
    }
}
