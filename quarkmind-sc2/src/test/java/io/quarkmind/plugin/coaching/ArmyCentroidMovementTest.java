package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ArmyCentroidMovementTest {

    private final LocationResolver resolver = new LocationResolver();

    @Test void retreat_distanceIncreased_satisfied() {
        var pred = new ArmyCentroidMovement(
            MovementDirection.RETREAT,
            new LocationReference.AbsolutePosition(50f, 50f),
            5.0,
            new Point2d(30f, 30f)
        );
        var state = stateWithArmyAt(new Point2d(20f, 20f));
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void retreat_distanceNotEnough_notSatisfied() {
        var pred = new ArmyCentroidMovement(
            MovementDirection.RETREAT,
            new LocationReference.AbsolutePosition(50f, 50f),
            20.0,
            new Point2d(30f, 30f)
        );
        var state = stateWithArmyAt(new Point2d(28f, 28f));
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void advance_distanceDecreased_satisfied() {
        var pred = new ArmyCentroidMovement(
            MovementDirection.ADVANCE,
            new LocationReference.AbsolutePosition(50f, 50f),
            5.0,
            new Point2d(20f, 20f)
        );
        var state = stateWithArmyAt(new Point2d(30f, 30f));
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void emptyArmy_baselineCapturedAsNull_notSatisfied() {
        var pred = new ArmyCentroidMovement(
            MovementDirection.RETREAT,
            new LocationReference.AbsolutePosition(50f, 50f),
            5.0,
            null
        );
        var state = stateWithArmyAt(new Point2d(20f, 20f));
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void currentArmyEmpty_notSatisfied() {
        var pred = new ArmyCentroidMovement(
            MovementDirection.RETREAT,
            new LocationReference.AbsolutePosition(50f, 50f),
            5.0,
            new Point2d(30f, 30f)
        );
        var state = new GameState(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void workersExcludedFromCentroid() {
        var units = List.of(
            new Unit("u1", UnitType.STALKER, new Point2d(30f, 30f), 100, 100, 50, 50, 0, 0),
            new Unit("u2", UnitType.PROBE, new Point2d(10f, 10f), 20, 20, 10, 10, 0, 0)
        );
        var pred = new ArmyCentroidMovement(
            MovementDirection.RETREAT,
            new LocationReference.AbsolutePosition(50f, 50f),
            5.0,
            new Point2d(40f, 40f)
        );
        var state = new GameState(0, 0, 0, 0, units, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void withBaseline_capturesArmyCentroid() {
        var pred = new ArmyCentroidMovement(MovementDirection.RETREAT,
            new LocationReference.AbsolutePosition(50f, 50f), 5.0, null);
        var state = stateWithArmyAt(new Point2d(25f, 25f));
        var baselined = (ArmyCentroidMovement) pred.withBaseline(state, resolver);
        assertThat(baselined.baselineCentroid()).isEqualTo(new Point2d(25f, 25f));
    }

    @Test void nullReferencePoint_notSatisfied() {
        var pred = new ArmyCentroidMovement(
            MovementDirection.RETREAT,
            new LocationReference.EnemyBase(),
            5.0,
            new Point2d(30f, 30f)
        );
        var state = stateWithArmyAt(new Point2d(20f, 20f));
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    private GameState stateWithArmyAt(Point2d pos) {
        var units = List.of(new Unit("u1", UnitType.STALKER, pos, 100, 100, 50, 50, 0, 0));
        return new GameState(0, 0, 0, 0, units, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }
}
