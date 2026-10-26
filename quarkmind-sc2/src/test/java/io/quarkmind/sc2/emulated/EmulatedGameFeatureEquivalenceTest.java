package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.replay.DivergenceReport;
import io.quarkmind.sc2.replay.ReplayValidationHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class EmulatedGameFeatureEquivalenceTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void compareEmulatedFeatureExtractionAgainstGroundTruth() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().limit(20).toList();
        }

        int totalRuns = 0;
        int[] unitMatchTotal = new int[CHECKPOINTS_MIN.length];
        int[] unitCountTotal = new int[CHECKPOINTS_MIN.length];
        int[] buildingMatchTotal = new int[CHECKPOINTS_MIN.length];
        int[] buildingCountTotal = new int[CHECKPOINTS_MIN.length];
        int[] upgradeMatchTotal = new int[CHECKPOINTS_MIN.length];
        int[] upgradeCountTotal = new int[CHECKPOINTS_MIN.length];
        int extractorEventCount = 0;

        for (Path replayPath : replays) {
            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    DivergenceReport report = ReplayValidationHarness.run(
                            replayPath, playerId, TICK_LIMIT);

                    var snapshots = reconstructGameStates(report);
                    if (snapshots.isEmpty()) continue;

                    var extractor = new EmulatedGameFeatureExtractor();
                    var gameJson = extractor.extract(snapshots, 1,
                            Race.PROTOSS, Race.TERRAN);

                    @SuppressWarnings("unchecked")
                    var events = (List<Map<String, Object>>) gameJson.get("trackerEvents");
                    extractorEventCount += events.size();

                    for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
                        int tickIndex = CHECKPOINTS_MIN[ci] * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= report.ticks().size()) continue;
                        var snap = report.ticks().get(tickIndex);

                        for (var entry : snap.groundTruthUnitsByType().entrySet()) {
                            int gt = entry.getValue();
                            int em = snap.emulatedUnitsByType().getOrDefault(entry.getKey(), 0);
                            unitCountTotal[ci] += gt;
                            if (em == gt) unitMatchTotal[ci] += gt;
                            else unitMatchTotal[ci] += Math.max(0, gt - Math.abs(gt - em));
                        }

                        for (var entry : snap.groundTruthBuildingsByType().entrySet()) {
                            int gt = entry.getValue();
                            int em = snap.emulatedBuildingsByType().getOrDefault(entry.getKey(), 0);
                            buildingCountTotal[ci] += gt;
                            if (em == gt) buildingMatchTotal[ci] += gt;
                            else buildingMatchTotal[ci] += Math.max(0, gt - Math.abs(gt - em));
                        }

                        upgradeCountTotal[ci] += snap.groundTruthUpgrades().size();
                        upgradeMatchTotal[ci] += (int) snap.groundTruthUpgrades().stream()
                                .filter(snap.emulatedUpgrades()::contains).count();
                    }
                    totalRuns++;
                } catch (Exception e) {
                    // skip failed replays
                }
            }
        }

        var sb = new StringBuilder();
        sb.append(String.format("%n=== EmulatedGame Feature Equivalence Report ===%n"));
        sb.append(String.format("Replays processed: %d player runs%n", totalRuns));
        sb.append(String.format("Total extractor events emitted: %d%n%n", extractorEventCount));

        for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
            int cp = CHECKPOINTS_MIN[ci];
            double unitAcc = unitCountTotal[ci] > 0 ?
                    100.0 * unitMatchTotal[ci] / unitCountTotal[ci] : 0;
            double bldgAcc = buildingCountTotal[ci] > 0 ?
                    100.0 * buildingMatchTotal[ci] / buildingCountTotal[ci] : 0;
            double upgAcc = upgradeCountTotal[ci] > 0 ?
                    100.0 * upgradeMatchTotal[ci] / upgradeCountTotal[ci] : 0;

            sb.append(String.format("%d-min: Units %.1f%% (%d/%d)  Buildings %.1f%% (%d/%d)  Upgrades %.1f%% (%d/%d)%n",
                    cp,
                    unitAcc, unitMatchTotal[ci], unitCountTotal[ci],
                    bldgAcc, buildingMatchTotal[ci], buildingCountTotal[ci],
                    upgAcc, upgradeMatchTotal[ci], upgradeCountTotal[ci]));
        }
        System.out.println(sb);

        assertThat(totalRuns).as("At least one replay processed").isGreaterThan(0);
        assertThat(extractorEventCount).as("Extractor emitted events").isGreaterThan(0);
    }

    private List<GameState> reconstructGameStates(DivergenceReport report) {
        var states = new ArrayList<GameState>();
        for (var tick : report.ticks()) {
            var units = expandUnits(tick.emulatedUnitsByType());
            var buildings = expandBuildings(tick.emulatedBuildingsByType());
            var gs = new GameState(
                    tick.emulatedMinerals(), tick.emulatedVespene(), 200, 0,
                    units, buildings, List.of(), List.of(),
                    List.of(), List.of(), List.of(),
                    (long) tick.tick() * SC2Data.LOOPS_PER_TICK,
                    null, tick.emulatedEconomy(), PlayerEconomyStats.EMPTY,
                    tick.emulatedUpgrades(), Set.of());
            states.add(gs);
        }
        return states;
    }

    private List<Unit> expandUnits(Map<UnitType, Integer> counts) {
        var units = new ArrayList<Unit>();
        for (var entry : counts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                units.add(new Unit("u" + units.size(), entry.getKey(),
                        new Point2d(0, 0), 100, 100, 0, 0, 0, 0));
            }
        }
        return units;
    }

    private List<Building> expandBuildings(Map<BuildingType, Integer> counts) {
        var buildings = new ArrayList<Building>();
        for (var entry : counts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                buildings.add(new Building("b" + buildings.size(), entry.getKey(),
                        new Point2d(0, 0), 1000, 1000, true));
            }
        }
        return buildings;
    }
}
