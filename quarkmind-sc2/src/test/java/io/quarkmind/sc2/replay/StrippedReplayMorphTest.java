package io.quarkmind.sc2.replay;

import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrippedReplayMorphTest {

    @Test
    void morphCommandEmitsSourceDeathAndTargetBirth() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph     = new ReplayCommand.MorphCommand(1000, "HighTemplar", "Archon");

        List<Map<String, Object>> events = extractor.processMorphForTest(morph, 1, 100);

        var deaths = events.stream()
                           .filter(e -> "UnitDied".equals(e.get("evtTypeName")))
                           .toList();
        var inits = events.stream()
                          .filter(e -> "UnitInit".equals(e.get("evtTypeName")))
                          .toList();
        var dones = events.stream()
                          .filter(e -> "UnitDone".equals(e.get("evtTypeName")))
                          .toList();

        // Archon merge: 2 source deaths (HighTemplar × 2)
        assertThat(deaths).hasSize(2);
        assertThat(deaths).allMatch(e -> "HighTemplar".equals(e.get("unitTypeName")));
        assertThat(deaths).allMatch(e -> ((Number) e.get("loop")).longValue() == 1000);

        // 1 target UnitInit + UnitDone (Archon)
        assertThat(inits).hasSize(1);
        assertThat(inits.get(0).get("unitTypeName")).isEqualTo("Archon");
        assertThat(dones).hasSize(1);
        assertThat(dones.get(0).get("unitTypeName")).isEqualTo("Archon");
    }

    @Test
    void standardUnitMorphEmitsSingleDeath() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph     = new ReplayCommand.MorphCommand(2000, "Zergling", "Baneling");

        List<Map<String, Object>> events = extractor.processMorphForTest(morph, 1, 100);

        var deaths = events.stream()
                           .filter(e -> "UnitDied".equals(e.get("evtTypeName")))
                           .toList();
        var inits = events.stream()
                          .filter(e -> "UnitInit".equals(e.get("evtTypeName")))
                          .toList();
        var dones = events.stream()
                          .filter(e -> "UnitDone".equals(e.get("evtTypeName")))
                          .toList();

        assertThat(deaths).hasSize(1);
        assertThat(deaths.get(0).get("unitTypeName")).isEqualTo("Zergling");

        assertThat(inits).hasSize(1);
        assertThat(inits.get(0).get("unitTypeName")).isEqualTo("Baneling");
        assertThat(((Number) inits.get(0).get("loop")).longValue()).isEqualTo(2000);

        assertThat(dones).hasSize(1);
        assertThat(dones.get(0).get("unitTypeName")).isEqualTo("Baneling");
        long expectedDoneLoop = 2000 + SC2Data.trainTimeInLoops(UnitType.BANELING);
        assertThat(((Number) dones.get(0).get("loop")).longValue()).isEqualTo(expectedDoneLoop);
    }

    @Test
    void morphTracksBanelingSpending() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph     = new ReplayCommand.MorphCommand(2000, "Zergling", "Baneling");

        var result = extractor.processMorphWithStateForTest(morph, 1, 100);

        assertThat(result.mineralsUsedArmy()).isEqualTo(25);
        assertThat(result.gasUsedArmy()).isEqualTo(25);
    }

    @Test
    void overseerMorphTracksSpendingWithZeroSupplyDelta() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph     = new ReplayCommand.MorphCommand(2000, "Overlord", "Overseer");

        var result = extractor.processMorphWithStateForTest(morph, 1, 100);

        assertThat(result.mineralsUsedArmy()).isEqualTo(50);
        assertThat(result.gasUsedArmy()).isEqualTo(50);
        int initialFoodUsed = SC2Data.INITIAL_SUPPLY_USED * 4096;
        assertThat(result.foodUsed()).isEqualTo(initialFoodUsed);
    }

    @Test
    void morphMultiplicationEmitsMultipleEvents() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph     = new ReplayCommand.MorphCommand(2000, "Zergling", "Baneling");

        var result = extractor.processMorphWithMultiplicationForTest(morph, 1, 100, 3);

        var deaths = result.events().stream()
                           .filter(e -> "UnitDied".equals(e.get("evtTypeName")))
                           .toList();
        var inits = result.events().stream()
                          .filter(e -> "UnitInit".equals(e.get("evtTypeName")))
                          .toList();
        assertThat(deaths).hasSize(3);
        assertThat(inits).hasSize(3);
    }

    @Test
    void buildingMorphIgnoresMultiplication() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph     = new ReplayCommand.MorphCommand(3000, "Hatchery", "Lair");

        var result = extractor.processMorphWithMultiplicationForTest(morph, 1, 100, 3);

        var deaths = result.events().stream()
                           .filter(e -> "UnitDied".equals(e.get("evtTypeName")))
                           .toList();
        assertThat(deaths).hasSize(1);
    }

    @Test
    void morphMultiplicationCappedAtMax() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph     = new ReplayCommand.MorphCommand(2000, "Zergling", "Baneling");

        var result = extractor.processMorphWithMultiplicationForTest(morph, 1, 100, 10);

        var deaths = result.events().stream()
                           .filter(e -> "UnitDied".equals(e.get("evtTypeName")))
                           .toList();
        assertThat(deaths).hasSize(4);
    }


    @Test
    void buildingMorphEmitsInitAndDone() {
        var extractor = new StrippedReplayFeatureExtractor();
        var morph = new ReplayCommand.MorphCommand(3000, "Hatchery", "Lair");

        List<Map<String, Object>> events = extractor.processMorphForTest(morph, 1, 100);

        var deaths = events.stream()
            .filter(e -> "UnitDied".equals(e.get("evtTypeName")))
            .toList();
        var inits = events.stream()
            .filter(e -> "UnitInit".equals(e.get("evtTypeName")))
            .toList();
        var dones = events.stream()
            .filter(e -> "UnitDone".equals(e.get("evtTypeName")))
            .toList();

        assertThat(deaths).hasSize(1);
        assertThat(deaths.get(0).get("unitTypeName")).isEqualTo("Hatchery");

        assertThat(inits).hasSize(1);
        assertThat(inits.get(0).get("unitTypeName")).isEqualTo("Lair");

        assertThat(dones).hasSize(1);
        assertThat(dones.get(0).get("unitTypeName")).isEqualTo("Lair");
    }

    @Test
    void warpGateAutoMorphOnUpgradeCompletion() {
        var extractor = new StrippedReplayFeatureExtractor();

        // Simulate: player built 2 Gateways, then researched WarpGateResearch
        var build1 = new ReplayCommand.BuildCommand(500, "Gateway", null);
        var build2 = new ReplayCommand.BuildCommand(600, "Gateway", null);
        var upgrade = new ReplayCommand.UpgradeCommand(1000, UpgradeType.WARP_GATE_RESEARCH.pythonName());

        List<Map<String, Object>> events = extractor.processWarpGateScenarioForTest(
            List.of(build1, build2), upgrade, 1, 100);

        // After WarpGateResearch completes, each Gateway dies and a WarpGate appears
        var gatewayDeaths = events.stream()
            .filter(e -> "UnitDied".equals(e.get("evtTypeName"))
                         && "Gateway".equals(e.get("unitTypeName")))
            .toList();
        var warpGateInits = events.stream()
            .filter(e -> "UnitInit".equals(e.get("evtTypeName"))
                         && "WarpGate".equals(e.get("unitTypeName")))
            .toList();
        var warpGateDones = events.stream()
            .filter(e -> "UnitDone".equals(e.get("evtTypeName"))
                         && "WarpGate".equals(e.get("unitTypeName")))
            .toList();

        assertThat(gatewayDeaths).hasSize(2);
        assertThat(warpGateInits).hasSize(2);
        assertThat(warpGateDones).hasSize(2);
    }

    @Test
    void zergBuildCommandEmitsDroneDeath() {
        var extractor = new StrippedReplayFeatureExtractor();

        List<Map<String, Object>> events = extractor.processZergBuildForTest(
            new ReplayCommand.BuildCommand(1500, "SpawningPool", null), 1, 100);

        var droneDeaths = events.stream()
            .filter(e -> "UnitDied".equals(e.get("evtTypeName"))
                         && "Drone".equals(e.get("unitTypeName")))
            .toList();

        assertThat(droneDeaths).hasSize(1);
        assertThat(((Number) droneDeaths.get(0).get("loop")).longValue()).isEqualTo(1500);
    }
}
