package io.quarkmind.plugin;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DroolsStrategyTaskStaticTest {

    @Test
    void firstFreeGeyser_returnsFirstWhenNoneOccupied() {
        var g1 = geyser(new Point2d(30, 30));
        var g2 = geyser(new Point2d(40, 40));
        Optional<Resource> result = DroolsStrategyTask.firstFreeGeyser(List.of(nexus()), List.of(g1, g2));
        assertThat(result).contains(g1);
    }

    @Test
    void firstFreeGeyser_skipsOccupiedAndReturnsNext() {
        var g1 = geyser(new Point2d(30, 30));
        var g2 = geyser(new Point2d(40, 40));
        var occupied = assimilator(new Point2d(30, 30));
        Optional<Resource> result = DroolsStrategyTask.firstFreeGeyser(List.of(nexus(), occupied), List.of(g1, g2));
        assertThat(result).contains(g2);
    }

    @Test
    void firstFreeGeyser_returnsEmptyWhenAllOccupied() {
        var g1 = geyser(new Point2d(30, 30));
        var a1 = assimilator(new Point2d(30, 30));
        Optional<Resource> result = DroolsStrategyTask.firstFreeGeyser(List.of(nexus(), a1), List.of(g1));
        assertThat(result).isEmpty();
    }

    @Test
    void firstFreeGeyser_returnsEmptyWhenNoGeysers() {
        Optional<Resource> result = DroolsStrategyTask.firstFreeGeyser(List.of(nexus()), List.of());
        assertThat(result).isEmpty();
    }

    private Resource geyser(Point2d pos) {
        return new Resource("g-0", pos, 2250);
    }

    private Building assimilator(Point2d pos) {
        return new Building("as-0", BuildingType.ASSIMILATOR, pos, 400, 400, true);
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(10, 10), 1500, 1500, true);
    }
}
