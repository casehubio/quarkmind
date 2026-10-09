package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ProtossRaceModelChronoTest {

    private ProtossRaceModel model;
    private PlayerState state;

    @BeforeEach
    void setUp() {
        model = new ProtossRaceModel();
        state = new PlayerState();
        model.seedInitialState(state, new ArrayList<>());
    }

    @Test
    void chronoBoost_deductsEnergy() {
        boolean result = model.handleAbility(state, "nexus-0", "CHRONO_BOOST", "nexus-0", 0L);
        assertTrue(result, "Chrono Boost should succeed with starting energy");
    }

    @Test
    void chronoBoost_insufficientEnergy_fails() {
        model.handleAbility(state, "nexus-0", "CHRONO_BOOST", "nexus-0", 0L);
        boolean result = model.handleAbility(state, "nexus-0", "CHRONO_BOOST", "nexus-0", 1L);
        assertFalse(result, "Second Chrono should fail — no energy left");
    }

    @Test
    void chronoBoost_appliesSpeedMultiplier() {
        model.handleAbility(state, "nexus-0", "CHRONO_BOOST", "nexus-0", 0L);
        double mult = model.trainingSpeedMultiplier("nexus-0", 0L);
        assertEquals(SC2Data.CHRONO_BOOST_MULTIPLIER, mult, 0.001);
    }

    @Test
    void chronoBoost_expiresAfterDuration() {
        model.handleAbility(state, "nexus-0", "CHRONO_BOOST", "nexus-0", 0L);
        long afterExpiry = SC2Data.CHRONO_BOOST_DURATION_LOOPS + 1;
        double mult = model.trainingSpeedMultiplier("nexus-0", afterExpiry);
        assertEquals(1.0, mult, 0.001);
    }

    @Test
    void energyRegenerates_overTime() {
        model.handleAbility(state, "nexus-0", "CHRONO_BOOST", "nexus-0", 0L);
        long loopsFor50Energy = (long) (SC2Data.CHRONO_BOOST_ENERGY_COST
            / SC2Data.NEXUS_ENERGY_REGEN_PER_LOOP);
        for (long loop = SC2Data.LOOPS_PER_TICK; loop <= loopsFor50Energy + SC2Data.LOOPS_PER_TICK; loop += SC2Data.LOOPS_PER_TICK) {
            model.tickPassive(state, loop);
        }
        boolean result = model.handleAbility(state, "nexus-0", "CHRONO_BOOST", "nexus-0", loopsFor50Energy + SC2Data.LOOPS_PER_TICK);
        assertTrue(result, "Should have regenerated enough energy for second Chrono");
    }

    @Test
    void newNexus_getsStartingEnergy() {
        model.onBuildingComplete(state, BuildingType.NEXUS, "nexus-1");
        boolean result = model.handleAbility(state, "nexus-1", "CHRONO_BOOST", "nexus-1", 0L);
        assertTrue(result, "New Nexus should have starting energy");
    }

    @Test
    void unknownAbility_returnsFalse() {
        boolean result = model.handleAbility(state, "nexus-0", "UNKNOWN", "nexus-0", 0L);
        assertFalse(result);
    }
}
