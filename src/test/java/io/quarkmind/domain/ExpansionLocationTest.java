package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ExpansionLocationTest {

    @Test void fromResources_emptyLists_returnsEmpty() {
        var result = ExpansionLocation.fromResources(List.of(), List.of(), new Point2d(0f, 0f));
        assertThat(result).isEmpty();
    }

    @Test void fromResources_singleCluster_returnsOneExpansion() {
        var minerals = List.of(
            new Resource("m1", new Point2d(10f, 10f), 1500),
            new Resource("m2", new Point2d(12f, 10f), 1500),
            new Resource("m3", new Point2d(14f, 10f), 1500)
        );
        var geysers = List.of(
            new Resource("g1", new Point2d(11f, 13f), 2250)
        );
        var result = ExpansionLocation.fromResources(minerals, geysers, new Point2d(10f, 10f));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).ordinal()).isEqualTo(0);
    }

    @Test void fromResources_twoClusters_orderedByDistanceFromStart() {
        var minerals = List.of(
            new Resource("m1", new Point2d(10f, 10f), 1500),
            new Resource("m2", new Point2d(12f, 10f), 1500),
            new Resource("m3", new Point2d(40f, 40f), 1500),
            new Resource("m4", new Point2d(42f, 40f), 1500)
        );
        var result = ExpansionLocation.fromResources(minerals, List.of(), new Point2d(8f, 8f));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).ordinal()).isEqualTo(0);
        assertThat(result.get(0).position().x()).isCloseTo(11f, within(0.1f));
        assertThat(result.get(1).ordinal()).isEqualTo(1);
        assertThat(result.get(1).position().x()).isCloseTo(41f, within(0.1f));
    }

    @Test void fromResources_clusteringRadius_doesNotMergeDistantGroups() {
        var minerals = List.of(
            new Resource("m1", new Point2d(10f, 10f), 1500),
            new Resource("m2", new Point2d(12f, 10f), 1500),
            new Resource("m3", new Point2d(50f, 50f), 1500),
            new Resource("m4", new Point2d(52f, 50f), 1500)
        );
        var result = ExpansionLocation.fromResources(minerals, List.of(), new Point2d(10f, 10f));
        assertThat(result).hasSize(2);
    }

    @Test void fromResources_geysersClusterWithMinerals() {
        var minerals = List.of(
            new Resource("m1", new Point2d(10f, 10f), 1500),
            new Resource("m2", new Point2d(12f, 10f), 1500)
        );
        var geysers = List.of(
            new Resource("g1", new Point2d(11f, 13f), 2250)
        );
        var result = ExpansionLocation.fromResources(minerals, geysers, new Point2d(10f, 10f));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).position().x()).isCloseTo(11f, within(0.1f));
        assertThat(result.get(0).position().y()).isCloseTo(11f, within(0.1f));
    }

    @Test void fromResources_threeExpansions_correctOrdinals() {
        var minerals = List.of(
            new Resource("m1", new Point2d(10f, 10f), 1500),
            new Resource("m2", new Point2d(12f, 10f), 1500),
            new Resource("m3", new Point2d(30f, 20f), 1500),
            new Resource("m4", new Point2d(32f, 20f), 1500),
            new Resource("m5", new Point2d(50f, 50f), 1500),
            new Resource("m6", new Point2d(52f, 50f), 1500)
        );
        var result = ExpansionLocation.fromResources(minerals, List.of(), new Point2d(10f, 10f));
        assertThat(result).hasSize(3);
        assertThat(result.get(0).ordinal()).isEqualTo(0);
        assertThat(result.get(1).ordinal()).isEqualTo(1);
        assertThat(result.get(2).ordinal()).isEqualTo(2);
    }

    @Test void result_isImmutable() {
        var minerals = List.of(new Resource("m1", new Point2d(10f, 10f), 1500));
        var result = ExpansionLocation.fromResources(minerals, List.of(), new Point2d(10f, 10f));
        assertThatThrownBy(() -> result.add(new ExpansionLocation(99, new Point2d(0f, 0f))))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
