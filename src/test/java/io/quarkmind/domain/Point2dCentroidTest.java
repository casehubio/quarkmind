package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class Point2dCentroidTest {

    record Pos(Point2d position) implements Positionable {}

    @Test void centroidOf_emptyList_returnsNull() {
        assertThat(Point2d.centroidOf(List.of())).isNull();
    }

    @Test void centroidOf_singleItem_returnsThatPosition() {
        var result = Point2d.centroidOf(List.of(new Pos(new Point2d(5f, 10f))));
        assertThat(result).isEqualTo(new Point2d(5f, 10f));
    }

    @Test void centroidOf_multipleItems_returnsAverage() {
        var items = List.of(
            new Pos(new Point2d(0f, 0f)),
            new Pos(new Point2d(10f, 0f)),
            new Pos(new Point2d(0f, 10f))
        );
        var result = Point2d.centroidOf(items);
        assertThat(result.x()).isCloseTo(3.333f, within(0.01f));
        assertThat(result.y()).isCloseTo(3.333f, within(0.01f));
    }

    @Test void centroidOf_twoItems_returnsMidpoint() {
        var items = List.of(
            new Pos(new Point2d(2f, 4f)),
            new Pos(new Point2d(8f, 12f))
        );
        var result = Point2d.centroidOf(items);
        assertThat(result).isEqualTo(new Point2d(5f, 8f));
    }

    @Test void centroidOf_nullList_returnsNull() {
        assertThat(Point2d.centroidOf(null)).isNull();
    }
}
