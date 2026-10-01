package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.details.Race;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrippedReplayFeatureExtractorTest {

    private static final Path LADDER_493 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    static boolean ladderReplaysExist() {
        if (!Files.isDirectory(LADDER_493)) return false;
        try (var stream = Files.list(LADDER_493)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void extractsBasicStructure() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        assertThat(gameJson).containsKey("trackerEvents");
        assertThat(gameJson).containsKey("ToonPlayerDescMap");
        assertThat(gameJson).containsKey("header");
        assertThat(gameJson).containsKey("metadata");

        @SuppressWarnings("unchecked")
        var header = (Map<String, Object>) gameJson.get("header");
        assertThat(header).containsKey("elapsedGameLoops");
        assertThat(((Number) header.get("elapsedGameLoops")).intValue()).isGreaterThan(0);

        @SuppressWarnings("unchecked")
        var playerMap = (Map<String, Object>) gameJson.get("ToonPlayerDescMap");
        assertThat(playerMap).containsKeys("1", "2");
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void extractsUnitBornEventsFromStrippedReplay() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");
        long unitBornCount = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .count();

        assertThat(unitBornCount).as("Must have synthetic UnitBorn events").isGreaterThan(0);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void extractsBuildingEvents() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");
        long unitInitCount = events.stream()
            .filter(e -> "UnitInit".equals(e.get("evtTypeName")))
            .count();
        long unitDoneCount = events.stream()
            .filter(e -> "UnitDone".equals(e.get("evtTypeName")))
            .count();

        assertThat(unitInitCount).as("Must have UnitInit events for buildings").isGreaterThan(0);
        assertThat(unitDoneCount).as("Must have UnitDone events for buildings").isGreaterThan(0);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void productionQueueDelaysSecondUnit() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        // Find the worker type for player 1
        @SuppressWarnings("unchecked")
        var playerMap = (Map<String, Object>) gameJson.get("ToonPlayerDescMap");
        @SuppressWarnings("unchecked")
        var p1 = (Map<String, Object>) playerMap.get("1");
        String race = (String) p1.get("race");
        String workerName = switch (race) {
            case "Protoss" -> "Probe";
            case "Terran" -> "SCV";
            case "Zerg" -> "Drone";
            default -> null;
        };
        if (workerName == null) return;

        List<Long> workerLoops = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName"))
                         && workerName.equals(e.get("unitTypeName"))
                         && Integer.valueOf(1).equals(e.get("controlPlayerId")))
            .map(e -> ((Number) e.get("loop")).longValue())
            .toList();

        if (workerLoops.size() >= 2) {
            assertThat(workerLoops.get(1) - workerLoops.get(0))
                .as("Second worker must be delayed by at least one train cycle")
                .isGreaterThanOrEqualTo(200);
        }
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void eventsAreSortedByLoop() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        for (int i = 1; i < events.size(); i++) {
            long prev = ((Number) events.get(i - 1).get("loop")).longValue();
            long curr = ((Number) events.get(i).get("loop")).longValue();
            assertThat(curr).as("Events must be sorted by loop (index %d)", i)
                .isGreaterThanOrEqualTo(prev);
        }
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void unitBornEventsUsePythonNames() throws Exception {
        Path replay = firstReplay();

        var extractor = new StrippedReplayFeatureExtractor();
        Map<String, Object> gameJson = extractor.extract(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) gameJson.get("trackerEvents");

        var unitNames = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .map(e -> (String) e.get("unitTypeName"))
            .distinct()
            .toList();

        // Must not contain Java enum names with underscores
        for (String name : unitNames) {
            assertThat(name).as("Unit name '%s' must be PascalCase (Python format)", name)
                .doesNotContain("_");
        }
    }


    @Test
    void preWarpGateStalkerEmitsUnitBorn() {
        var extractor = new StrippedReplayFeatureExtractor();
        var train     = new TrainIntent("gw1", UnitType.STALKER);
        List<Map<String, Object>> events = extractor.processTrainForTest(
                train, 3000L, 1, 100, 0L);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("evtTypeName")).isEqualTo("UnitBorn");
        assertThat(events.get(0).get("unitTypeName")).isEqualTo("Stalker");
    }

    @Test
    void warpGatedStalkerEmitsUnitInit() {
        var  extractor    = new StrippedReplayFeatureExtractor();
        var  train        = new TrainIntent("gw1", UnitType.STALKER);
        long warpGateDone = 2500L;
        List<Map<String, Object>> events = extractor.processTrainForTest(
                train, 3000L, 1, 100, warpGateDone);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("evtTypeName")).isEqualTo("UnitInit");
        assertThat(events.get(0).get("unitTypeName")).isEqualTo("Stalker");
        assertThat(events.get(1).get("evtTypeName")).isEqualTo("UnitDone");
        assertThat(events.get(1).get("unitTypeName")).isEqualTo("Stalker");
    }

    @Test
    void nonGatewayUnitStillEmitsUnitBornAfterWarpGate() {
        var  extractor    = new StrippedReplayFeatureExtractor();
        var  train        = new TrainIntent("robo1", UnitType.IMMORTAL);
        long warpGateDone = 2500L;
        List<Map<String, Object>> events = extractor.processTrainForTest(
                train, 3000L, 1, 100, warpGateDone);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("evtTypeName")).isEqualTo("UnitBorn");
        assertThat(events.get(0).get("unitTypeName")).isEqualTo("Immortal");
    }

    @Test
    void warpGatedZealotAtExactCompletionLoopEmitsUnitInit() {
        var  extractor    = new StrippedReplayFeatureExtractor();
        var  train        = new TrainIntent("gw1", UnitType.ZEALOT);
        long warpGateDone = 3000L;
        List<Map<String, Object>> events = extractor.processTrainForTest(
                train, 3000L, 1, 100, warpGateDone);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("evtTypeName")).isEqualTo("UnitInit");
        assertThat(events.get(0).get("unitTypeName")).isEqualTo("Zealot");
        assertThat(events.get(1).get("evtTypeName")).isEqualTo("UnitDone");
    }


    @Test
    void archonMergeEmitsUnitInitNotUnitBorn() {
        var                       extractor = new StrippedReplayFeatureExtractor();
        var                       morph     = new ReplayCommand.MorphCommand(3000L, "HighTemplar", "Archon");
        List<Map<String, Object>> events    = extractor.processMorphForTest(morph, 1, 100);

        var archonEvents = events.stream()
                                 .filter(e -> "Archon".equals(e.get("unitTypeName")))
                                 .toList();

        assertThat(archonEvents).hasSize(2);
        assertThat(archonEvents.get(0).get("evtTypeName")).isEqualTo("UnitInit");
        assertThat(archonEvents.get(1).get("evtTypeName")).isEqualTo("UnitDone");
    }

    @Test
    void newBuildingTypesHavePythonNames() {
        assertThat(StrippedReplayFeatureExtractor.buildingTypeToPythonName(BuildingType.CREEP_TUMOR))
                .isEqualTo("CreepTumor");
        assertThat(StrippedReplayFeatureExtractor.buildingTypeToPythonName(BuildingType.CREEP_TUMOR_QUEEN))
                .isEqualTo("CreepTumorQueen");
        assertThat(StrippedReplayFeatureExtractor.buildingTypeToPythonName(BuildingType.NYDUS_CANAL))
                .isEqualTo("NydusCanal");
        assertThat(StrippedReplayFeatureExtractor.buildingTypeToPythonName(BuildingType.ORACLE_STASIS_TRAP))
                .isEqualTo("OracleStasisTrap");
        assertThat(StrippedReplayFeatureExtractor.buildingTypeToPythonName(BuildingType.ASSIMILATOR_RICH))
                .isEqualTo("AssimilatorRich");
        assertThat(StrippedReplayFeatureExtractor.buildingTypeToPythonName(BuildingType.LURKER_DEN))
                .isEqualTo("LurkerDenMP");
    }


    private Path firstReplay() throws Exception {
        try (var stream = Files.list(LADDER_493)) {
            return stream
                .filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted()
                .findFirst()
                .orElseThrow(() -> new AssertionError("No replays found"));
        }
    }

    // --- Larva auto-spawn tests ---

    @Test
    void larvaAutoSpawnCapsAtThreePerBase() {
        var extractor = new StrippedReplayFeatureExtractor();
        var events = extractor.emitAutoSpawnedLarvaForTest(
            1, Race.ZERG, List.of(), List.of(new long[]{0, 0}), 10000, 1);
        long larvaCount = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .filter(e -> "Larva".equals(e.get("unitTypeName")))
            .count();
        assertThat(larvaCount).as("Should cap at 3 Larva per base").isEqualTo(3);
    }

    @Test
    void larvaConsumptionResumesSpawning() {
        var extractor = new StrippedReplayFeatureExtractor();
        int interval = SC2Data.LARVA_SPAWN_INTERVAL;
        long consumeLoop = (long) interval * 4;
        var events = extractor.emitAutoSpawnedLarvaForTest(
            1, Race.ZERG, List.of(consumeLoop), List.of(new long[]{0, 0}), 20000, 1);
        long larvaCount = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .filter(e -> "Larva".equals(e.get("unitTypeName")))
            .count();
        assertThat(larvaCount).as("Consuming Larva should allow more spawning").isGreaterThan(3);
    }

    @Test
    void larvaMultipleBasesSpawnIndependently() {
        var extractor = new StrippedReplayFeatureExtractor();
        var events = extractor.emitAutoSpawnedLarvaForTest(
            1, Race.ZERG, List.of(),
            List.of(new long[]{0, 0}, new long[]{1, 2000}), 20000, 1);
        long larvaCount = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .filter(e -> "Larva".equals(e.get("unitTypeName")))
            .count();
        assertThat(larvaCount).as("Two bases should produce 6 Larva (3 each)").isEqualTo(6);
    }

    @Test
    void nonZergProducesNoLarva() {
        var extractor = new StrippedReplayFeatureExtractor();
        var events = extractor.emitAutoSpawnedLarvaForTest(
            1, Race.TERRAN, List.of(), List.of(new long[]{0, 0}), 10000, 1);
        assertThat(events).isEmpty();
    }

    @Test
    void larvaSpawnTimingCorrect() {
        var extractor = new StrippedReplayFeatureExtractor();
        int interval = SC2Data.LARVA_SPAWN_INTERVAL;
        var events = extractor.emitAutoSpawnedLarvaForTest(
            1, Race.ZERG, List.of(), List.of(new long[]{0, 0}), 10000, 1);
        var loops = events.stream()
            .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
            .map(e -> ((Number) e.get("loop")).longValue())
            .toList();
        assertThat(loops).containsExactly((long) interval, (long) interval * 2, (long) interval * 3);
    }
}
