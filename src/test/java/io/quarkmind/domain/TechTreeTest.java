package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class TechTreeTest {

    TechTree tree = new TechTree();

    // --- Protoss ---

    @Test
    void zealot_requiresGateway() {
        assertThat(tree.canTrain(UnitType.ZEALOT, Set.of())).isFalse();
        assertThat(tree.canTrain(UnitType.ZEALOT, Set.of(BuildingType.GATEWAY))).isTrue();
    }

    @Test
    void stalker_requiresGatewayAndCyberneticsCore() {
        assertThat(tree.canTrain(UnitType.STALKER, Set.of(BuildingType.GATEWAY))).isFalse();
        assertThat(tree.canTrain(UnitType.STALKER,
            Set.of(BuildingType.GATEWAY, BuildingType.CYBERNETICS_CORE))).isTrue();
    }

    @Test
    void colossus_requiresRoboFacilityAndRoboBay() {
        assertThat(tree.canTrain(UnitType.COLOSSUS, Set.of(BuildingType.ROBOTICS_FACILITY))).isFalse();
        assertThat(tree.canTrain(UnitType.COLOSSUS,
            Set.of(BuildingType.ROBOTICS_FACILITY, BuildingType.ROBOTICS_BAY))).isTrue();
    }

    // --- Zerg ---

    @Test
    void zergling_requiresSpawningPool() {
        assertThat(tree.canTrain(UnitType.ZERGLING, Set.of())).isFalse();
        assertThat(tree.canTrain(UnitType.ZERGLING, Set.of(BuildingType.SPAWNING_POOL))).isTrue();
    }

    @Test
    void roach_requiresRoachWarren() {
        assertThat(tree.canTrain(UnitType.ROACH, Set.of())).isFalse();
        assertThat(tree.canTrain(UnitType.ROACH, Set.of(BuildingType.ROACH_WARREN))).isTrue();
    }

    @Test
    void hydralisk_requiresHydraliskDen() {
        assertThat(tree.canTrain(UnitType.HYDRALISK, Set.of(BuildingType.SPAWNING_POOL))).isFalse();
        assertThat(tree.canTrain(UnitType.HYDRALISK, Set.of(BuildingType.HYDRALISK_DEN))).isTrue();
    }

    // --- Terran ---

    @Test
    void marine_requiresBarracks() {
        assertThat(tree.canTrain(UnitType.MARINE, Set.of())).isFalse();
        assertThat(tree.canTrain(UnitType.MARINE, Set.of(BuildingType.BARRACKS))).isTrue();
    }

    @Test
    void marauder_requiresBarracks() {
        // TECH_LAB is an add-on and not modelled as a BuildingType in the domain.
        // Marauder prerequisite is BARRACKS only at the building level.
        assertThat(tree.canTrain(UnitType.MARAUDER, Set.of())).isFalse();
        assertThat(tree.canTrain(UnitType.MARAUDER, Set.of(BuildingType.BARRACKS))).isTrue();
    }

    // --- nextRequired ---

    @Test
    void nextRequired_returnsFirstMissingPrereq() {
        assertThat(tree.nextRequired(UnitType.STALKER, Set.of()))
            .contains(BuildingType.GATEWAY);
        assertThat(tree.nextRequired(UnitType.STALKER, Set.of(BuildingType.GATEWAY)))
            .contains(BuildingType.CYBERNETICS_CORE);
        assertThat(tree.nextRequired(UnitType.STALKER,
            Set.of(BuildingType.GATEWAY, BuildingType.CYBERNETICS_CORE)))
            .isEmpty();
    }

    @Test
    void probe_requiresNoPrereqs() {
        assertThat(tree.canTrain(UnitType.PROBE, Set.of())).isTrue();
        assertThat(tree.nextRequired(UnitType.PROBE, Set.of())).isEmpty();
    }

    @Test
    void observer_withRoboticsFacility_canTrain() {
        // OBSERVER is mapped with ROBOTICS_FACILITY as its prereq;
        // providing that building satisfies canTrain.
        assertThat(tree.canTrain(UnitType.OBSERVER, Set.of(BuildingType.ROBOTICS_FACILITY))).isTrue();
    }

    @Test
    void overseer_requiresHatcheryAndLair() {
        assertThat(tree.nextRequired(UnitType.OVERSEER, Set.of()))
            .contains(BuildingType.HATCHERY);
        assertThat(tree.nextRequired(UnitType.OVERSEER, Set.of(BuildingType.HATCHERY)))
            .contains(BuildingType.LAIR);
        assertThat(tree.canTrain(UnitType.OVERSEER,
            Set.of(BuildingType.HATCHERY, BuildingType.LAIR))).isTrue();
    }
}
