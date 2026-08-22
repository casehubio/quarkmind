package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ExpansionPlacementTest {

    private final LocationResolver resolver = new LocationResolver();

    @Test void newBaseNearTarget_satisfied() {
        var pred = new ExpansionPlacement(
            new LocationReference.AbsolutePosition(30f, 30f), 5.0, Set.of("b0"));
        var state = stateWithBuildings(
            new Building("b0", BuildingType.NEXUS, new Point2d(10f, 10f), 1500, 1500, true),
            new Building("b1", BuildingType.NEXUS, new Point2d(31f, 31f), 1500, 1500, true)
        );
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void noNewBase_notSatisfied() {
        var pred = new ExpansionPlacement(
            new LocationReference.AbsolutePosition(30f, 30f), 5.0, Set.of("b0"));
        var state = stateWithBuildings(
            new Building("b0", BuildingType.NEXUS, new Point2d(10f, 10f), 1500, 1500, true)
        );
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void newBaseAtWrongLocation_notSatisfied() {
        var pred = new ExpansionPlacement(
            new LocationReference.AbsolutePosition(30f, 30f), 5.0, Set.of("b0"));
        var state = stateWithBuildings(
            new Building("b0", BuildingType.NEXUS, new Point2d(10f, 10f), 1500, 1500, true),
            new Building("b1", BuildingType.NEXUS, new Point2d(50f, 50f), 1500, 1500, true)
        );
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void withBaseline_capturesTags() {
        var pred = new ExpansionPlacement(
            new LocationReference.AbsolutePosition(30f, 30f), 5.0, Set.of());
        var state = stateWithBuildings(
            new Building("b0", BuildingType.NEXUS, new Point2d(10f, 10f), 1500, 1500, true)
        );
        var baselined = (ExpansionPlacement) pred.withBaseline(state, resolver);
        assertThat(baselined.baselineBaseTags()).containsExactly("b0");
    }

    @Test void terranTownHalls_recognized() {
        var pred = new ExpansionPlacement(
            new LocationReference.AbsolutePosition(30f, 30f), 5.0, Set.of("b0"));
        var state = stateWithBuildings(
            new Building("b0", BuildingType.COMMAND_CENTER, new Point2d(10f, 10f), 1500, 1500, true),
            new Building("b1", BuildingType.ORBITAL_COMMAND, new Point2d(31f, 31f), 1500, 1500, true)
        );
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void zergTownHalls_recognized() {
        var pred = new ExpansionPlacement(
            new LocationReference.AbsolutePosition(30f, 30f), 5.0, Set.of("b0"));
        var state = stateWithBuildings(
            new Building("b0", BuildingType.HATCHERY, new Point2d(10f, 10f), 1500, 1500, true),
            new Building("b1", BuildingType.LAIR, new Point2d(31f, 31f), 1500, 1500, true)
        );
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void nullTarget_notSatisfied() {
        var pred = new ExpansionPlacement(
            new LocationReference.EnemyBase(), 5.0, Set.of());
        var state = stateWithBuildings(
            new Building("b1", BuildingType.NEXUS, new Point2d(31f, 31f), 1500, 1500, true)
        );
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    private GameState stateWithBuildings(Building... buildings) {
        return new GameState(0, 0, 0, 0, List.of(), List.of(buildings), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }
}
