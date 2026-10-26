package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmulatedGameFeatureShapeTest {

    @Test
    void extractedGameJsonHasCorrectEventStructure() {
        var game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT);
        game.reset();

        var snapshots = new ArrayList<GameState>();
        for (int tick = 0; tick < 500; tick++) {
            game.tick();
            if (tick % 16 == 0) {
                snapshots.add(game.snapshot());
            }
        }

        var extractor = new EmulatedGameFeatureExtractor();
        var gameJson = extractor.extract(snapshots, 16, Race.PROTOSS, Race.TERRAN);

        assertThat(gameJson).containsKeys("ToonPlayerDescMap", "trackerEvents", "header", "metadata");

        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) gameJson.get("trackerEvents");
        assertThat(events).isNotEmpty();

        var statsEvents = events.stream()
                .filter(e -> "PlayerStats".equals(e.get("evtTypeName")))
                .toList();
        assertThat(statsEvents).isNotEmpty();

        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) statsEvents.getFirst().get("stats");
        assertThat(stats).hasSize(13);
        assertThat(stats).containsKeys(
                "scoreValueMineralsCurrent", "scoreValueVespeneCurrent",
                "scoreValueMineralsCollectionRate", "scoreValueVespeneCollectionRate",
                "scoreValueFoodMade", "scoreValueFoodUsed",
                "scoreValueWorkersActiveCount",
                "scoreValueMineralsUsedCurrentArmy", "scoreValueMineralsUsedCurrentEconomy",
                "scoreValueMineralsUsedCurrentTechnology",
                "scoreValueVespeneUsedCurrentArmy", "scoreValueVespeneUsedCurrentEconomy",
                "scoreValueVespeneUsedCurrentTechnology");

        for (var value : stats.values()) {
            assertThat(value).isInstanceOf(Long.class);
        }

        var unitEvents = events.stream()
                .filter(e -> {
                    var t = (String) e.get("evtTypeName");
                    return "UnitBorn".equals(t) || "UnitDied".equals(t)
                           || "UnitInit".equals(t) || "UnitDone".equals(t);
                })
                .toList();
        for (var evt : unitEvents) {
            assertThat(evt).containsKeys("evtTypeName", "loop", "controlPlayerId",
                    "unitTypeName", "unitTagIndex", "unitTagRecycle");
        }

        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) gameJson.get("metadata");
        assertThat(metadata.get("extractionMethod")).isEqualTo("emulator");
    }

    @Test
    void sc2NameConversionMatchesPythonExpectations() {
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("ZEALOT")).isEqualTo("Zealot");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("DARK_TEMPLAR")).isEqualTo("DarkTemplar");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("COMMAND_CENTER")).isEqualTo("CommandCenter");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("SCV")).isEqualTo("SCV");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("MULE")).isEqualTo("MULE");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("HELLBAT")).isEqualTo("HellionTank");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("VIKING")).isEqualTo("VikingFighter");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("GATEWAY")).isEqualTo("Gateway");
        assertThat(EmulatedGameFeatureExtractor.toSc2Name("CYBERNETICS_CORE")).isEqualTo("CyberneticsCore");
    }

    @Test
    void emulatedGameSnapshotsProduceEventsForInitialProbes() {
        var game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT);
        game.reset();

        var extractor = new EmulatedGameFeatureExtractor();
        extractor.addSnapshot(game.snapshot(), 0);

        var gameJson = extractor.finalise(Race.PROTOSS, Race.TERRAN);
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        var probes = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .filter(e -> "Probe".equals(e.get("unitTypeName")))
                .toList();
        assertThat(probes).as("Initial probes should appear as UnitBorn events")
                .hasSizeGreaterThanOrEqualTo(1);
    }
}
