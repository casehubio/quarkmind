package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CountDeltaTest {

    private final LocationResolver resolver = new LocationResolver();

    @Test void withBaseline_capturesCurrentUnitCount() {
        var pred = new CountDelta(UnitType.STALKER, null, 3, 0);
        var state = stateWithUnits(UnitType.STALKER, 2);
        var baselined = (CountDelta) pred.withBaseline(state, resolver);
        assertThat(baselined.baselineCount()).isEqualTo(2);
    }

    @Test void isSatisfied_deltaReached_returnsTrue() {
        var pred = new CountDelta(UnitType.STALKER, null, 3, 2);
        var state = stateWithUnits(UnitType.STALKER, 5);
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void isSatisfied_deltaNotReached_returnsFalse() {
        var pred = new CountDelta(UnitType.STALKER, null, 3, 2);
        var state = stateWithUnits(UnitType.STALKER, 3);
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    @Test void isSatisfied_buildingType() {
        var pred = new CountDelta(null, BuildingType.NEXUS, 1, 1);
        var state = stateWithBuildings(BuildingType.NEXUS, 2);
        assertThat(pred.isSatisfied(state, resolver)).isTrue();
    }

    @Test void isSatisfied_neitherTypeSet_returnsFalse() {
        var pred = new CountDelta(null, null, 1, 0);
        var state = stateWithUnits(UnitType.STALKER, 5);
        assertThat(pred.isSatisfied(state, resolver)).isFalse();
    }

    private GameState stateWithUnits(UnitType type, int count) {
        var units = new java.util.ArrayList<Unit>();
        for (int i = 0; i < count; i++) {
            units.add(new Unit("u" + i, type, new Point2d(0f, 0f), 100, 100, 50, 50, 0, 0));
        }
        return new GameState(0, 0, 0, 0, units, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null);
    }

    private GameState stateWithBuildings(BuildingType type, int count) {
        var buildings = new java.util.ArrayList<Building>();
        for (int i = 0; i < count; i++) {
            buildings.add(new Building("b" + i, type, new Point2d(0f, 0f), 1000, 1000, true));
        }
        return new GameState(0, 0, 0, 0, List.of(), buildings, List.of(), List.of(), List.of(), List.of(), List.of(), 0L, null);
    }
}
