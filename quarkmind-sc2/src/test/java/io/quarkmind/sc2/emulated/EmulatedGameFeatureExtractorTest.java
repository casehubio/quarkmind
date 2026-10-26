package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmulatedGameFeatureExtractorTest {

    @Test
    void emitsUnitBornWhenUnitCountIncreases() {
        var extractor = new EmulatedGameFeatureExtractor();

        extractor.addSnapshot(gameState(0, List.of(), List.of(), List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 0);
        extractor.addSnapshot(gameState(16, List.of(unit(UnitType.ZEALOT)), List.of(),
                List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 16);

        var events = trackerEvents(extractor.finalise(Race.PROTOSS, Race.TERRAN));
        var unitBorns = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .toList();
        assertThat(unitBorns).hasSize(1);
        assertThat(unitBorns.getFirst().get("unitTypeName")).isEqualTo("Zealot");
        assertThat(unitBorns.getFirst().get("loop")).isEqualTo(16L);
        assertThat(unitBorns.getFirst().get("controlPlayerId")).isEqualTo(1);
    }

    @Test
    void emitsUnitDiedWhenUnitCountDecreases() {
        var extractor = new EmulatedGameFeatureExtractor();
        extractor.addSnapshot(gameState(0,
                List.of(unit(UnitType.ZEALOT), unit(UnitType.ZEALOT)), List.of(),
                List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 0);
        extractor.addSnapshot(gameState(32,
                List.of(unit(UnitType.ZEALOT)), List.of(),
                List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 32);

        var events = trackerEvents(extractor.finalise(Race.PROTOSS, Race.TERRAN));
        var died = events.stream()
                .filter(e -> "UnitDied".equals(e.get("evtTypeName")))
                .toList();
        assertThat(died).hasSize(1);
        assertThat(died.getFirst().get("unitTypeName")).isEqualTo("Zealot");
    }

    @Test
    void emitsBuildingInitAndDoneWhenBuildingAppears() {
        var extractor = new EmulatedGameFeatureExtractor();
        extractor.addSnapshot(gameState(0, List.of(), List.of(), List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 0);
        extractor.addSnapshot(gameState(100, List.of(),
                List.of(building(BuildingType.GATEWAY)),
                List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 100);

        var events = trackerEvents(extractor.finalise(Race.PROTOSS, Race.TERRAN));
        var inits = events.stream().filter(e -> "UnitInit".equals(e.get("evtTypeName"))).toList();
        var dones = events.stream().filter(e -> "UnitDone".equals(e.get("evtTypeName"))).toList();
        assertThat(inits).hasSize(1);
        assertThat(dones).hasSize(1);
        assertThat(inits.getFirst().get("unitTypeName")).isEqualTo("Gateway");
    }

    @Test
    void emitsUpgradeWhenNewUpgradeAppears() {
        var extractor = new EmulatedGameFeatureExtractor();
        extractor.addSnapshot(gameState(0, List.of(), List.of(), List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 0);
        extractor.addSnapshot(gameState(3200, List.of(), List.of(), List.of(), List.of(),
                Set.of("Stimpack"), Set.of(),
                PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 3200);

        var events = trackerEvents(extractor.finalise(Race.TERRAN, Race.PROTOSS));
        var upgrades = events.stream()
                .filter(e -> "Upgrade".equals(e.get("evtTypeName")))
                .toList();
        assertThat(upgrades).hasSize(1);
        assertThat(upgrades.getFirst().get("upgradeTypeName")).isEqualTo("Stimpack");
        assertThat(upgrades.getFirst().get("playerId")).isEqualTo(1);
    }

    @Test
    void emitsPlayerStatsEvery160Loops() {
        var extractor = new EmulatedGameFeatureExtractor();
        var eco = new PlayerEconomyStats(400, 100, 1200, 300,
                30, 18, 16, 500, 800, 200, 100, 0, 150);

        for (int loop = 0; loop <= 320; loop += 16) {
            extractor.addSnapshot(
                    gameState(loop, List.of(), List.of(), List.of(), List.of(),
                            Set.of(), Set.of(), eco, PlayerEconomyStats.EMPTY),
                    loop);
        }
        var events = trackerEvents(extractor.finalise(Race.PROTOSS, Race.TERRAN));

        var stats = events.stream()
                .filter(e -> "PlayerStats".equals(e.get("evtTypeName")))
                .filter(e -> (int) e.get("controlPlayerId") == 1)
                .toList();
        assertThat(stats).hasSize(3);
        @SuppressWarnings("unchecked")
        var firstStats = (Map<String, Object>) stats.getFirst().get("stats");
        assertThat(firstStats.get("scoreValueMineralsCurrent")).isEqualTo(400_000L);
        assertThat(firstStats.get("scoreValueWorkersActiveCount")).isEqualTo(16_000L);
        assertThat(firstStats.get("scoreValueFoodMade")).isEqualTo(30L);
    }

    @Test
    void batchExtractProducesCompleteGameJson() {
        var snapshots = List.of(
                gameState(0, List.of(), List.of(), List.of(), List.of(),
                        Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY),
                gameState(16, List.of(unit(UnitType.PROBE)), List.of(),
                        List.of(), List.of(),
                        Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY));

        var extractor = new EmulatedGameFeatureExtractor();
        var gameJson = extractor.extract(snapshots, 16, Race.PROTOSS, Race.TERRAN);

        assertThat(gameJson).containsKeys("ToonPlayerDescMap", "trackerEvents", "header", "metadata");
        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) gameJson.get("metadata");
        assertThat(metadata.get("extractionMethod")).isEqualTo("emulator");
    }

    @Test
    void enemyUnitEventsUsePlayerId2() {
        var extractor = new EmulatedGameFeatureExtractor();
        extractor.addSnapshot(gameState(0, List.of(), List.of(), List.of(), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 0);
        extractor.addSnapshot(gameState(50, List.of(), List.of(),
                List.of(unit(UnitType.MARINE)), List.of(),
                Set.of(), Set.of(), PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY), 50);

        var events = trackerEvents(extractor.finalise(Race.PROTOSS, Race.TERRAN));
        var born = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .findFirst().orElseThrow();
        assertThat(born.get("controlPlayerId")).isEqualTo(2);
        assertThat(born.get("unitTypeName")).isEqualTo("Marine");
    }

    // --- helpers ---

    static GameState gameState(long frame,
                               List<Unit> myUnits, List<Building> myBuildings,
                               List<Unit> enemyUnits, List<Building> enemyBuildings,
                               Set<String> playerUpgrades, Set<String> enemyUpgrades,
                               PlayerEconomyStats playerEconomy,
                               PlayerEconomyStats enemyEconomy) {
        return new GameState(50, 0, 15, 6,
                myUnits, myBuildings, enemyUnits, enemyBuildings,
                List.of(), List.of(), List.of(),
                frame, null, playerEconomy, enemyEconomy,
                playerUpgrades, enemyUpgrades);
    }

    static Unit unit(UnitType type) {
        return new Unit("t0", type, new Point2d(0, 0), 100, 100, 0, 0, 0, 0);
    }

    static Building building(BuildingType type) {
        return new Building("b0", type, new Point2d(0, 0), 1000, 1000, true);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> trackerEvents(Map<String, Object> gameJson) {
        return (List<Map<String, Object>>) gameJson.get("trackerEvents");
    }
}
