package io.quarkmind.agent;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StateBasedPhaseResolverTest {

    private final StateBasedPhaseResolver resolver = new StateBasedPhaseResolver();

    static GameState state(long frame, int supplyUsed, List<Building> buildings) {
        return new GameState(0, 0, 200, supplyUsed, List.of(), buildings, List.of(), List.of(), List.of(), List.of(), List.of(), frame, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }

    static Building building(BuildingType type) {
        return new Building("b-1", type, new Point2d(0, 0), 100, 100, true);
    }

    static long minutesToFrames(double minutes) {
        return Math.round(minutes * 60 * SC2Data.GAME_LOOPS_PER_SECOND);
    }

    // --- EARLY ---

    @Test
    void early_oneBase_noTech_lowSupply() {
        var gs = state(minutesToFrames(4), 30, List.of(building(BuildingType.NEXUS)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.EARLY);
    }

    @Test
    void early_emptyBuildings_gameStart() {
        var gs = state(0, 0, List.of());
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.EARLY);
    }

    // --- MID via expansion ---

    @Test
    void mid_twoNexus() {
        var gs = state(minutesToFrames(5), 40, List.of(
            building(BuildingType.NEXUS), building(BuildingType.NEXUS)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    @Test
    void mid_terranTwoBases() {
        var gs = state(minutesToFrames(5), 40, List.of(
            building(BuildingType.COMMAND_CENTER), building(BuildingType.ORBITAL_COMMAND)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    @Test
    void mid_zergTwoBases() {
        var gs = state(minutesToFrames(5), 40, List.of(
            building(BuildingType.HATCHERY), building(BuildingType.LAIR)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    // --- MID via tech ---

    @Test
    void mid_oneBase_tier2Tech() {
        var gs = state(minutesToFrames(5), 30, List.of(
            building(BuildingType.NEXUS), building(BuildingType.ROBOTICS_FACILITY)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    @Test
    void mid_oneBase_tier3Tech_noTier2() {
        var gs = state(minutesToFrames(5), 30, List.of(
            building(BuildingType.NEXUS), building(BuildingType.FLEET_BEACON)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    // --- MID via supply ---

    @Test
    void mid_supply60() {
        var gs = state(minutesToFrames(5), 60, List.of(building(BuildingType.NEXUS)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    // --- LATE via expansion + tech ---

    @Test
    void late_threeBasesAndTier3() {
        var gs = state(minutesToFrames(15), 120, List.of(
            building(BuildingType.NEXUS), building(BuildingType.NEXUS),
            building(BuildingType.NEXUS), building(BuildingType.FLEET_BEACON)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.LATE);
    }

    // --- LATE via supply ---

    @Test
    void late_supply150() {
        var gs = state(minutesToFrames(15), 150, List.of(building(BuildingType.NEXUS)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.LATE);
    }

    // --- Time floors ---

    @Test
    void timeFloor_midSignalsBefore3min_clampsToEarly() {
        var gs = state(minutesToFrames(2.5), 70, List.of(
            building(BuildingType.NEXUS), building(BuildingType.NEXUS)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.EARLY);
    }

    @Test
    void timeFloor_lateSignalsBefore8min_clampsToMid() {
        var gs = state(minutesToFrames(6), 160, List.of(
            building(BuildingType.NEXUS), building(BuildingType.NEXUS),
            building(BuildingType.NEXUS), building(BuildingType.FLEET_BEACON)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    // --- Zerg morph chain ---

    @Test
    void zergMorphChain_hiveNoTier2_isMid() {
        var gs = state(minutesToFrames(12), 80, List.of(
            building(BuildingType.HIVE)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    // --- Under-construction buildings count ---

    @Test
    void underConstruction_countsTowardSignals() {
        Building incomplete = new Building("b-1", BuildingType.ROBOTICS_FACILITY,
            new Point2d(0, 0), 50, 100, false);
        var gs = state(minutesToFrames(5), 30, List.of(
            building(BuildingType.NEXUS), incomplete));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }

    // --- All races expansion detection ---

    @ParameterizedTest
    @EnumSource(value = BuildingType.class, names = {
        "NEXUS", "COMMAND_CENTER", "ORBITAL_COMMAND", "PLANETARY_FORTRESS",
        "HATCHERY", "LAIR", "HIVE"})
    void expansion_allRaceTypes(BuildingType baseType) {
        var gs = state(minutesToFrames(5), 40, List.of(
            building(baseType), building(baseType)));
        assertThat(resolver.resolve(gs)).isEqualTo(GamePhase.MID);
    }
}
