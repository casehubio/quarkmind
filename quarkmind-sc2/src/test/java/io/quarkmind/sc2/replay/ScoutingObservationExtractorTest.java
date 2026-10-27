package io.quarkmind.sc2.replay;

import io.quarkmind.domain.ScoutingObservation;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScoutingObservationExtractorTest {

    @Test
    void extractFirstBuilding_barracks() {
        var gameJson = buildGameJson(List.of(
            unitInit("SupplyDepot", 1, 405),
            unitInit("Barracks", 1, 914)
        ), "Terran", "Zerg");

        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        var firstBuilding = findObs(obs, "first_building");

        assertEquals("barracks", firstBuilding.state());
        assertEquals(914, firstBuilding.detectedAtLoop());
    }

    @Test
    void extractFirstBuilding_skipsSupplyBuildings() {
        var gameJson = buildGameJson(List.of(
            unitInit("Pylon", 1, 300),
            unitInit("Gateway", 1, 800)
        ), "Protoss", "Terran");

        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("gateway", findObs(obs, "first_building").state());
    }

    @Test
    void extractGasTiming_early() {
        var gameJson = buildGameJson(List.of(
            unitInit("Refinery", 1, 1500)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("early", findObs(obs, "gas_timing").state());
    }

    @Test
    void extractGasTiming_normal() {
        var gameJson = buildGameJson(List.of(
            unitInit("Assimilator", 1, 2500)
        ), "Protoss", "Terran");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("normal", findObs(obs, "gas_timing").state());
    }

    @Test
    void extractGasTiming_late() {
        var gameJson = buildGameJson(List.of(
            unitInit("Extractor", 1, 4000)
        ), "Zerg", "Protoss");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("late", findObs(obs, "gas_timing").state());
    }

    @Test
    void extractGasTiming_none_whenNoGas() {
        var gameJson = buildGameJson(List.of(
            unitInit("Barracks", 1, 900)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("none", findObs(obs, "gas_timing").state());
    }

    @Test
    void extractExpansionTiming_fast() {
        var gameJson = buildGameJson(List.of(
            unitInit("Hatchery", 2, 1600)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 2);
        assertEquals("fast", findObs(obs, "expansion_timing").state());
    }

    @Test
    void extractExpansionTiming_normal() {
        var gameJson = buildGameJson(List.of(
            unitInit("CommandCenter", 1, 3000)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("normal", findObs(obs, "expansion_timing").state());
    }

    @Test
    void extractExpansionTiming_late() {
        var gameJson = buildGameJson(List.of(
            unitInit("Nexus", 1, 5000)
        ), "Protoss", "Terran");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("late", findObs(obs, "expansion_timing").state());
    }

    @Test
    void extractExpansionTiming_none_whenNoExpansion() {
        var gameJson = buildGameJson(List.of(
            unitInit("Gateway", 1, 800)
        ), "Protoss", "Terran");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("none", findObs(obs, "expansion_timing").state());
    }

    @Test
    void extractTechPath_factory() {
        var gameJson = buildGameJson(List.of(
            unitInit("SupplyDepot", 1, 400),
            unitInit("Barracks", 1, 900),
            unitInit("Factory", 1, 2500)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("factory", findObs(obs, "tech_path").state());
    }

    @Test
    void extractTechPath_stargate() {
        var gameJson = buildGameJson(List.of(
            unitInit("Gateway", 1, 700),
            unitInit("Stargate", 1, 2000)
        ), "Protoss", "Terran");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("stargate", findObs(obs, "tech_path").state());
    }

    @Test
    void extractTechPath_spawningPool_isZergTech() {
        var gameJson = buildGameJson(List.of(
            unitInit("SpawningPool", 2, 1100)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 2);
        assertEquals("spawningpool", findObs(obs, "tech_path").state());
    }

    @Test
    void extractArmyComposition_light() {
        var gameJson = buildGameJson(List.of(
            unitBorn("Marine", 1, 3000),
            unitBorn("Marine", 1, 3200),
            unitBorn("Marine", 1, 3400),
            unitBorn("Marine", 1, 3600),
            unitBorn("Marine", 1, 3800)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("light", findObs(obs, "army_composition").state());
    }

    @Test
    void extractArmyComposition_air() {
        var gameJson = buildGameJson(List.of(
            unitBorn("Phoenix", 1, 4000),
            unitBorn("Phoenix", 1, 4500),
            unitBorn("Phoenix", 1, 5000),
            unitBorn("Zealot", 1, 3000)
        ), "Protoss", "Terran");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("air", findObs(obs, "army_composition").state());
    }

    @Test
    void extractArmyComposition_mixed() {
        var gameJson = buildGameJson(List.of(
            unitBorn("Marine", 1, 3000),
            unitBorn("Marine", 1, 3200),
            unitBorn("SiegeTank", 1, 4000),
            unitBorn("SiegeTank", 1, 4500),
            unitBorn("Banshee", 1, 5000)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("mixed", findObs(obs, "army_composition").state());
    }

    @Test
    void extractArmyComposition_none_whenNoMilitary() {
        var gameJson = buildGameJson(List.of(
            unitBorn("SCV", 1, 300),
            unitBorn("SCV", 1, 500)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("none", findObs(obs, "army_composition").state());
    }

    @Test
    void extractWorkerSaturation_saturated() {
        var events = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 18; i++) {
            events.add(unitBorn("SCV", 1, 300 + i * 200));
        }
        var gameJson = buildGameJson(events, "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("saturated", findObs(obs, "worker_saturation").state());
    }

    @Test
    void extractWorkerSaturation_under() {
        var events = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 10; i++) {
            events.add(unitBorn("Probe", 1, 300 + i * 200));
        }
        var gameJson = buildGameJson(events, "Protoss", "Terran");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("under", findObs(obs, "worker_saturation").state());
    }

    @Test
    void extractAggressionTiming_earlyPressure() {
        var gameJson = buildGameJson(List.of(
            unitBorn("Zergling", 2, 2500),
            unitBorn("Zergling", 2, 2500)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 2);
        assertEquals("early_pressure", findObs(obs, "aggression_timing").state());
    }

    @Test
    void extractAggressionTiming_timingAttack() {
        var gameJson = buildGameJson(List.of(
            unitBorn("Marine", 1, 4500)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("timing_attack", findObs(obs, "aggression_timing").state());
    }

    @Test
    void extractAggressionTiming_noAggression_whenOnlyWorkers() {
        var gameJson = buildGameJson(List.of(
            unitBorn("SCV", 1, 300)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        assertEquals("no_aggression", findObs(obs, "aggression_timing").state());
    }

    @Test
    void extractOnlyForRequestedPlayer() {
        var gameJson = buildGameJson(List.of(
            unitInit("Barracks", 1, 900),
            unitInit("SpawningPool", 2, 1100)
        ), "Terran", "Zerg");
        var obs1 = ScoutingObservationExtractor.extract(gameJson, 1);
        var obs2 = ScoutingObservationExtractor.extract(gameJson, 2);
        assertEquals("barracks", findObs(obs1, "first_building").state());
        assertEquals("spawningpool", findObs(obs2, "first_building").state());
    }

    @Test
    void observationsAreSortedByLoop() {
        var gameJson = buildGameJson(List.of(
            unitInit("Barracks", 1, 900),
            unitInit("Refinery", 1, 1050),
            unitInit("Factory", 1, 3368),
            unitBorn("Marine", 1, 4459)
        ), "Terran", "Zerg");
        var obs = ScoutingObservationExtractor.extract(gameJson, 1);
        for (int i = 1; i < obs.size(); i++) {
            assertTrue(obs.get(i).detectedAtLoop() >= obs.get(i - 1).detectedAtLoop(),
                "Observations not sorted at index " + i);
        }
    }

    private ScoutingObservation findObs(List<ScoutingObservation> obs, String variable) {
        return obs.stream().filter(o -> o.variable().equals(variable)).findFirst().orElseThrow(
            () -> new AssertionError("No observation for variable: " + variable));
    }

    static Map<String, Object> buildGameJson(
            List<Map<String, Object>> events, String race1, String race2) {
        Map<String, Object> p1 = new HashMap<>();
        p1.put("playerID", 1);
        p1.put("race", race1);
        p1.put("result", "Win");
        Map<String, Object> p2 = new HashMap<>();
        p2.put("playerID", 2);
        p2.put("race", race2);
        p2.put("result", "Loss");
        return Map.of(
            "ToonPlayerDescMap", Map.of("1", p1, "2", p2),
            "trackerEvents", events,
            "header", Map.of("elapsedGameLoops", 10000, "baseBuild", 75025),
            "metadata", Map.of("mapName", "TestMap")
        );
    }

    static Map<String, Object> unitInit(String typeName, int playerId, int loop) {
        return Map.of(
            "evtTypeName", "UnitInit",
            "unitTypeName", typeName,
            "controlPlayerId", playerId,
            "loop", loop
        );
    }

    static Map<String, Object> unitBorn(String typeName, int playerId, int loop) {
        return Map.of(
            "evtTypeName", "UnitBorn",
            "unitTypeName", typeName,
            "controlPlayerId", playerId,
            "loop", loop
        );
    }
}
