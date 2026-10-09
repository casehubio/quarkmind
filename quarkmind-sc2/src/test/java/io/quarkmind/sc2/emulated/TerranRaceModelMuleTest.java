package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TerranRaceModelMuleTest {

    private TerranRaceModel model;
    private PlayerState state;

    @BeforeEach
    void setUp() {
        model = new TerranRaceModel();
        state = new PlayerState();
        model.seedInitialState(state, new ArrayList<>());
    }

    @Test
    void muleCalldown_failsWithoutOC() {
        boolean result = model.handleAbility(state, "cc-0", "MULE_CALLDOWN", null, 0L);
        assertFalse(result, "MULE should fail — CC is not an OC");
    }

    @Test
    void muleCalldown_succeedsWithOC() {
        morphToOC();
        model.onBuildingComplete(state, BuildingType.ORBITAL_COMMAND, "cc-0");
        boolean result = model.handleAbility(state, "cc-0", "MULE_CALLDOWN", null, 0L);
        assertTrue(result, "MULE should succeed — OC has starting energy");
    }

    @Test
    void muleCalldown_deductsEnergy() {
        morphToOC();
        model.onBuildingComplete(state, BuildingType.ORBITAL_COMMAND, "cc-0");
        model.handleAbility(state, "cc-0", "MULE_CALLDOWN", null, 0L);
        boolean result = model.handleAbility(state, "cc-0", "MULE_CALLDOWN", null, 1L);
        assertFalse(result, "Second MULE should fail — no energy left");
    }

    @Test
    void muleCalldown_spawnsMule() {
        morphToOC();
        model.onBuildingComplete(state, BuildingType.ORBITAL_COMMAND, "cc-0");
        long unitsBefore = state.units().stream().filter(u -> u.type() == UnitType.MULE).count();
        model.handleAbility(state, "cc-0", "MULE_CALLDOWN", null, 0L);
        long unitsAfter = state.units().stream().filter(u -> u.type() == UnitType.MULE).count();
        assertEquals(unitsBefore + 1, unitsAfter, "MULE should be spawned");
    }

    @Test
    void ocEnergyRegenerates() {
        morphToOC();
        model.onBuildingComplete(state, BuildingType.ORBITAL_COMMAND, "cc-0");
        model.handleAbility(state, "cc-0", "MULE_CALLDOWN", null, 0L);
        long loopsFor50Energy = (long) (SC2Data.CHRONO_BOOST_ENERGY_COST
            / SC2Data.NEXUS_ENERGY_REGEN_PER_LOOP);
        for (long loop = SC2Data.LOOPS_PER_TICK; loop <= loopsFor50Energy + SC2Data.LOOPS_PER_TICK; loop += SC2Data.LOOPS_PER_TICK) {
            model.tickPassive(state, loop);
        }
        boolean result = model.handleAbility(state, "cc-0", "MULE_CALLDOWN", null, loopsFor50Energy + SC2Data.LOOPS_PER_TICK);
        assertTrue(result, "Should have regenerated enough energy for second MULE");
    }

    @Test
    void unknownAbility_returnsFalse() {
        boolean result = model.handleAbility(state, "cc-0", "UNKNOWN", null, 0L);
        assertFalse(result);
    }

    private void morphToOC() {
        state.replaceAllBuildings(b -> b.tag().equals("cc-0")
            ? new Building("cc-0", BuildingType.ORBITAL_COMMAND, b.position(),
                b.health(), b.maxHealth(), true)
            : b);
    }
}
