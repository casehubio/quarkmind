package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveScoutingObservationExtractorTest {

    @Test
    void firstBuilding_emitsOnce() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs1 = gameState(List.of(building(BuildingType.GATEWAY)), List.of(), 2000);
        var obs1 = extractor.extract(gs1);

        assertTrue(obs1.stream().anyMatch(o ->
            o.variable().equals("first_building") && o.state().equals("gateway")));

        var gs2 = gameState(List.of(building(BuildingType.GATEWAY),
            building(BuildingType.CYBERNETICS_CORE)), List.of(), 3000);
        var obs2 = extractor.extract(gs2);

        assertTrue(obs2.stream().noneMatch(o -> o.variable().equals("first_building")),
            "first_building should only fire once");
    }

    @Test
    void firstBuilding_excludesBases() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.NEXUS)), List.of(), 2000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().noneMatch(o -> o.variable().equals("first_building")));
    }

    @Test
    void firstBuilding_excludesSupplyAndGas() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(
            building(BuildingType.PYLON), building(BuildingType.ASSIMILATOR)), List.of(), 2000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().noneMatch(o -> o.variable().equals("first_building")));
    }

    @Test
    void firstBuilding_nameMatchesCptVocabulary() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.SPAWNING_POOL)), List.of(), 2000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("first_building") && o.state().equals("spawningpool")));
    }

    @Test
    void gasTimingEarly_emitsOnce() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.ASSIMILATOR)), List.of(), 1500);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("gas_timing") && o.state().equals("early")));

        var obs2 = extractor.extract(gs);
        assertTrue(obs2.stream().noneMatch(o -> o.variable().equals("gas_timing")));
    }

    @Test
    void gasTimingNormal() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.EXTRACTOR)), List.of(), 2500);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("gas_timing") && o.state().equals("normal")));
    }

    @Test
    void gasTimingLate() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.REFINERY)), List.of(), 4000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("gas_timing") && o.state().equals("late")));
    }

    @Test
    void expansionFast_whenTwoBasesEarly() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(
            building(BuildingType.HATCHERY), building(BuildingType.HATCHERY)),
            List.of(), 2000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("expansion_timing") && o.state().equals("fast")));
    }

    @Test
    void expansionNone_whenSingleBase() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.NEXUS)), List.of(), 5000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().noneMatch(o -> o.variable().equals("expansion_timing")));
    }

    @Test
    void techPath_emitsFirstTechBuilding() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.TWILIGHT_COUNCIL)), List.of(), 3000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("tech_path") && o.state().equals("twilightcouncil")));
    }

    @Test
    void armyComposition_reEmitsOnChange() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs1 = gameState(List.of(), List.of(
            unit(UnitType.ZERGLING), unit(UnitType.ZERGLING)), 1000);
        var obs1 = extractor.extract(gs1);
        assertTrue(obs1.stream().anyMatch(o ->
            o.variable().equals("army_composition") && o.state().equals("light")));

        var gs2 = gameState(List.of(), List.of(
            unit(UnitType.ROACH), unit(UnitType.ROACH), unit(UnitType.ROACH)), 2000);
        var obs2 = extractor.extract(gs2);
        assertTrue(obs2.stream().anyMatch(o ->
            o.variable().equals("army_composition") && o.state().equals("armored")));
    }

    @Test
    void armyComposition_noEnemies_none() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(), List.of(), 1000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("army_composition") && o.state().equals("none")));
    }

    @Test
    void armyComposition_airDominant() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(), List.of(
            unit(UnitType.MUTALISK), unit(UnitType.MUTALISK), unit(UnitType.ZERGLING)), 3000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("army_composition") && o.state().equals("air")));
    }

    @Test
    void armyComposition_doesNotReEmitSameState() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(), List.of(unit(UnitType.ZERGLING)), 1000);
        extractor.extract(gs);
        var obs2 = extractor.extract(gameState(List.of(), List.of(unit(UnitType.ZERGLING)), 2000));
        assertTrue(obs2.stream().noneMatch(o -> o.variable().equals("army_composition")));
    }

    @Test
    void workerSaturation_under() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(building(BuildingType.NEXUS)),
            List.of(unit(UnitType.PROBE)), 1000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("worker_saturation") && o.state().equals("under")));
    }

    @Test
    void aggression_earlyPressure() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(), List.of(unit(UnitType.ZERGLING)), 3000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("aggression_timing") && o.state().equals("early_pressure")));
    }

    @Test
    void aggression_noAggression_whenOnlyWorkers() {
        var extractor = new LiveScoutingObservationExtractor();
        var gs = gameState(List.of(), List.of(unit(UnitType.DRONE)), 3000);
        var obs = extractor.extract(gs);
        assertTrue(obs.stream().anyMatch(o ->
            o.variable().equals("aggression_timing") && o.state().equals("no_aggression")));
    }

    @Test
    void reset_clearsAllState() {
        var extractor = new LiveScoutingObservationExtractor();
        extractor.extract(gameState(
            List.of(building(BuildingType.GATEWAY)), List.of(), 2000));
        extractor.reset();
        var obs = extractor.extract(gameState(
            List.of(building(BuildingType.GATEWAY)), List.of(), 3000));
        assertTrue(obs.stream().anyMatch(o -> o.variable().equals("first_building")),
            "After reset, first_building should fire again");
    }

    @Test
    void toObservationName_matchesCptVocabulary() {
        assertEquals("spawningpool", LiveScoutingObservationExtractor.toObservationName(
            BuildingType.SPAWNING_POOL));
        assertEquals("twilightcouncil", LiveScoutingObservationExtractor.toObservationName(
            BuildingType.TWILIGHT_COUNCIL));
        assertEquals("roboticsfacility", LiveScoutingObservationExtractor.toObservationName(
            BuildingType.ROBOTICS_FACILITY));
        assertEquals("barracks", LiveScoutingObservationExtractor.toObservationName(
            BuildingType.BARRACKS));
    }

    static GameState gameState(List<Building> enemyBuildings,
                               List<Unit> enemyUnits, long frame) {
        return new GameState(0, 0, 0, 0, List.of(), List.of(),
            enemyUnits, enemyBuildings, List.of(), List.of(), List.of(),
            frame, null,
            PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY,
            Set.of(), Set.of());
    }

    static Building building(BuildingType type) {
        return new Building("b1", type, new Point2d(50, 50), 1000, 1000, true);
    }

    static Unit unit(UnitType type) {
        return new Unit("u1", type, new Point2d(50, 50), 100, 100, 0, 0, 0, 0);
    }
}
