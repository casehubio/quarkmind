package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentExecutionListenerTest {

    record TrainEvent(int tick, TrainIntent intent, String buildingTag,
                      boolean success, String reason) {}

    @Test
    void listenerFiresOnSuccessfulTrain() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.reset();

        List<TrainEvent> events = new ArrayList<>();
        game.setIntentExecutionListener((tick, intent, bldgTag, success, reason) ->
            events.add(new TrainEvent(tick, intent, bldgTag, success, reason)));

        game.tick();

        String nexusTag = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst().map(Building::tag).orElseThrow();
        game.applyIntent(new TrainIntent(nexusTag, UnitType.PROBE));

        assertFalse(events.isEmpty(), "Listener should have fired");
        assertTrue(events.get(0).success());
        assertEquals(UnitType.PROBE, events.get(0).intent().unitType());
        assertEquals(nexusTag, events.get(0).buildingTag());
    }

    @Test
    void listenerFiresOnBuildingNotFound() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.reset();
        game.tick();

        List<TrainEvent> events = new ArrayList<>();
        game.setIntentExecutionListener((tick, intent, bldgTag, success, reason) ->
            events.add(new TrainEvent(tick, intent, bldgTag, success, reason)));

        // Use non-prefixed tag — "r-" prefix triggers auto-resolution fallback to any townhall
        game.applyIntent(new TrainIntent("nonexistent", UnitType.MARINE));

        assertFalse(events.isEmpty(), "Listener should have fired");
        assertFalse(events.get(0).success());
        assertEquals("building_not_found", events.get(0).reason());
    }

    @Test
    void noListenerDoesNotThrow() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.reset();
        game.tick();

        String nexusTag = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst().map(Building::tag).orElseThrow();
        assertDoesNotThrow(() -> game.applyIntent(new TrainIntent(nexusTag, UnitType.PROBE)));
    }


    @Test
    void trackerDrivenIntentReroutesToCorrectBuildingType() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.TERRAN));
        game.reset();
        game.tick();

        Building barracks = new Building("r-barracks-1", BuildingType.BARRACKS,
                                         new io.quarkmind.domain.Point2d(30, 30), 1000, 1000, true);
        game.injectReplayBuilding(barracks);
        game.markReplayBuildingComplete("r-barracks-1");

        Building factory = new Building("r-factory-1", BuildingType.FACTORY,
                                        new io.quarkmind.domain.Point2d(35, 30), 1250, 1250, true);
        game.injectReplayBuilding(factory);
        game.markReplayBuildingComplete("r-factory-1");

        List<TrainEvent> events = new ArrayList<>();
        game.setIntentExecutionListener((tick, intent, bldgTag, success, reason) ->
                                                events.add(new TrainEvent(tick, intent, bldgTag, success, reason)));

        game.applyIntent(new TrainIntent("r-factory-1", UnitType.MARINE));

        assertFalse(events.isEmpty(), "Listener should have fired");
        assertTrue(events.get(0).success(),
                   "Should reroute to Barracks: " + events.get(0).reason());
        assertEquals("r-barracks-1", events.get(0).buildingTag());
    }

    @Test
    void trackerDrivenIntentRejectsWhenNoCorrectBuildingExists() {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.TERRAN));
        game.reset();
        game.tick();

        Building factory = new Building("r-factory-1", BuildingType.FACTORY,
                                        new io.quarkmind.domain.Point2d(35, 30), 1250, 1250, true);
        game.injectReplayBuilding(factory);
        game.markReplayBuildingComplete("r-factory-1");

        List<TrainEvent> events = new ArrayList<>();
        game.setIntentExecutionListener((tick, intent, bldgTag, success, reason) ->
                                                events.add(new TrainEvent(tick, intent, bldgTag, success, reason)));

        game.applyIntent(new TrainIntent("r-factory-1", UnitType.MARINE));

        assertFalse(events.isEmpty(), "Listener should have fired");
        assertFalse(events.get(0).success(),
                    "Should still reject when no correct building exists");
    }
}
