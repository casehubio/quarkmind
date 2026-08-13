package io.quarkmind.agent;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("benchmark")
class MapControlCalibrationTest {

    private static final Path AI_ARENA_DIR = Path.of("replays/aiarena_protoss");
    private static final Path IEM10_ZIP = Path.of("replays/2016_IEM_10_Taipei.zip");

    @Test
    void expansionControlRadius_basesWithinThreshold() throws IOException {
        double radius = MultiFactorDominanceAssessor.EXPANSION_CONTROL_RADIUS;
        int totalBases = 0;
        int matched = 0;
        List<Double> distances = new ArrayList<>();

        StringBuilder report = new StringBuilder();
        report.append(String.format("%n=== MapControl Calibration (EXPANSION_CONTROL_RADIUS=%.1f) ===%n", radius));
        report.append(String.format("%-50s %8s %8s %8s%n", "Replay", "Bases", "Matched", "MaxDist"));

        List<IEM10JsonSimulatedGame> iem10Games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        for (IEM10JsonSimulatedGame game : iem10Games) {
            GameState state = game.snapshot();
            var result = measureBases(state, radius);
            totalBases += result.total;
            matched += result.matched;
            distances.addAll(result.distances);

            report.append(String.format("%-50s %8d %8d %8.1f%n",
                game.replayName().substring(0, Math.min(50, game.replayName().length())),
                result.total, result.matched,
                result.distances.isEmpty() ? 0 : result.distances.stream().mapToDouble(d -> d).max().orElse(0)));
        }

        List<Path> aiArenaFiles = Files.list(AI_ARENA_DIR)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted()
            .collect(Collectors.toList());
        for (Path replay : aiArenaFiles) {
            try {
                ReplaySimulatedGame game = new ReplaySimulatedGame(replay, 1);
                GameState state = game.snapshot();
                var result = measureBases(state, radius);
                totalBases += result.total;
                matched += result.matched;
                distances.addAll(result.distances);

                report.append(String.format("%-50s %8d %8d %8.1f%n",
                    replay.getFileName().toString(),
                    result.total, result.matched,
                    result.distances.isEmpty() ? 0 : result.distances.stream().mapToDouble(d -> d).max().orElse(0)));
            } catch (IllegalArgumentException e) {
                // skip unparseable replays
            }
        }

        if (!distances.isEmpty()) {
            double minDist = distances.stream().mapToDouble(d -> d).min().orElse(0);
            double maxDist = distances.stream().mapToDouble(d -> d).max().orElse(0);
            double avgDist = distances.stream().mapToDouble(d -> d).average().orElse(0);
            report.append(String.format("%nBase-to-centroid distances: min=%.1f, max=%.1f, avg=%.1f%n", minDist, maxDist, avgDist));
        }
        double pct = totalBases > 0 ? 100.0 * matched / totalBases : 0;
        report.append(String.format("Total: %d/%d bases within %.1f of expansion centroid (%.1f%%)%n", matched, totalBases, radius, pct));

        System.out.println(report);

        assertThat(totalBases).as("should find bases across replay datasets").isGreaterThan(0);
        assertThat(matched).as("bases within EXPANSION_CONTROL_RADIUS")
            .isGreaterThanOrEqualTo((int) (totalBases * 0.90));
    }

    private static MeasureResult measureBases(GameState state, double radius) {
        List<Resource> minerals = state.mineralPatches();
        List<Resource> geysers = state.geysers();
        Point2d playerStart = findPlayerStart(state);
        if (minerals.isEmpty()) return new MeasureResult(0, 0, List.of());

        List<ExpansionLocation> expansions = ExpansionLocation.fromResources(minerals, geysers, playerStart);
        if (expansions.isEmpty()) return new MeasureResult(0, 0, List.of());

        List<Building> bases = new java.util.ArrayList<>();
        for (Building b : state.myBuildings()) {
            if (b.isComplete() && SC2Data.isBase(b.type())) bases.add(b);
        }
        for (Building b : state.enemyBuildings()) {
            if (b.isComplete() && SC2Data.isBase(b.type())) bases.add(b);
        }

        int total = bases.size();
        int matched = 0;
        List<Double> distances = new ArrayList<>();
        for (Building base : bases) {
            double minDist = expansions.stream()
                .mapToDouble(exp -> base.position().distanceTo(exp.position()))
                .min().orElse(Double.MAX_VALUE);
            distances.add(minDist);
            if (minDist <= radius) matched++;
        }
        return new MeasureResult(total, matched, distances);
    }

    private record MeasureResult(int total, int matched, List<Double> distances) {}

    private static Point2d findPlayerStart(GameState state) {
        return state.myBuildings().stream()
            .filter(b -> b.type() == BuildingType.NEXUS
                      || b.type() == BuildingType.COMMAND_CENTER
                      || b.type() == BuildingType.HATCHERY)
            .map(Building::position)
            .findFirst()
            .orElse(new Point2d(0, 0));
    }
}
