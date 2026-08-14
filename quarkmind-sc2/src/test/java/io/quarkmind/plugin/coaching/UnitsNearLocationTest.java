package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class UnitsNearLocationTest {

    private final LocationResolver resolver = new LocationResolver();

    @Test void unitsWithinRadius_satisfied() {
        var pred = new UnitsNearLocation(UnitType.STALKER,
            new LocationReference.AbsolutePosition(10f, 10f), 5.0, 2);
        var state = stateWithUnitsAt(
            new Unit("u1", UnitType.STALKER, new Point2d(11f, 11f), 100, 100, 50, 50, 0, 0),
            new Unit("u2", UnitType.STALKER, new Point2d(12f, 10f), 100, 100, 50, 50, 0, 0)
        );
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void unitsOutsideRadius_notSatisfied() {
        var pred = new UnitsNearLocation(UnitType.STALKER,
            new LocationReference.AbsolutePosition(10f, 10f), 5.0, 2);
        var state = stateWithUnitsAt(
            new Unit("u1", UnitType.STALKER, new Point2d(11f, 11f), 100, 100, 50, 50, 0, 0),
            new Unit("u2", UnitType.STALKER, new Point2d(50f, 50f), 100, 100, 50, 50, 0, 0)
        );
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void nullUnitType_countsAllNonWorkers() {
        var pred = new UnitsNearLocation(null,
            new LocationReference.AbsolutePosition(10f, 10f), 5.0, 2);
        var state = stateWithUnitsAt(
            new Unit("u1", UnitType.STALKER, new Point2d(11f, 11f), 100, 100, 50, 50, 0, 0),
            new Unit("u2", UnitType.ZEALOT, new Point2d(12f, 10f), 100, 100, 0, 0, 0, 0),
            new Unit("u3", UnitType.PROBE, new Point2d(10f, 10f), 20, 20, 10, 10, 0, 0)
        );
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void workersExcludedWhenUnitTypeNull() {
        var pred = new UnitsNearLocation(null,
            new LocationReference.AbsolutePosition(10f, 10f), 5.0, 2);
        var state = stateWithUnitsAt(
            new Unit("u1", UnitType.STALKER, new Point2d(11f, 11f), 100, 100, 50, 50, 0, 0),
            new Unit("u2", UnitType.PROBE, new Point2d(10f, 10f), 20, 20, 10, 10, 0, 0)
        );
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void withBaseline_returnsSelf() {
        var pred = new UnitsNearLocation(UnitType.STALKER,
            new LocationReference.AbsolutePosition(10f, 10f), 5.0, 1);
        var state = stateWithUnitsAt();
        assertThat(pred.withBaseline(state, resolver)).isSameAs(pred);
    }

    @Test void nullTarget_notSatisfied() {
        var pred = new UnitsNearLocation(UnitType.STALKER,
            new LocationReference.EnemyBase(), 5.0, 1);
        var state = stateWithUnitsAt(
            new Unit("u1", UnitType.STALKER, new Point2d(11f, 11f), 100, 100, 50, 50, 0, 0)
        );
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    private GameState stateWithUnitsAt(Unit... units) {
        return new GameState(0, 0, 0, 0, List.of(units), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null);
    }
}
