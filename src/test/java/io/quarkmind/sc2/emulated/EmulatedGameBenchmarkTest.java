package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.TechTree;
import io.quarkmind.domain.TerrainGrid;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.LongStream;

/**
 * EmulatedGame throughput benchmark — full-engine-tick rate with realistic combat load.
 *
 * NOT part of the regular test suite. Run explicitly:
 *   mvn test -Pbenchmark -Dtest=EmulatedGameBenchmarkTest
 *
 * Output is printed to stdout AND written to target/emulated-benchmark-results.txt.
 * Copy into docs/benchmarks/YYYY-MM-DD-l7-emulated.md to record a snapshot.
 *
 * Setup mirrors EmulatedEngine.joinGame() for all EmulatedGame-affecting calls: TerrainGrid, PathfindingMovement,
 * RaceModelFactory, EnemyBehavior with PROTOSS_4GATE (FAST_PUSH — first attack ~tick 100).
 * 120 warmup ticks advance past the first attack; 50 measured ticks capture steady-state combat.
 * Note: {@code terrainProvider.setTerrain(grid)} is omitted — it wires a CDI bean
 * not present in the benchmark context and has no effect on EmulatedGame internals.
 */
@Tag("benchmark")
class EmulatedGameBenchmarkTest {

    private static final int WARMUP_TICKS  = 120;
    private static final int MEASURE_TICKS = 50;

    @Test
    void emulatedGameThroughput() throws IOException {
        // Mirror EmulatedEngine.joinGame() exactly
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = TerrainGrid.emulatedMap();
        game.setMovementStrategy(new PathfindingMovement(grid));
        game.setTerrainGrid(grid);
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.setEnemyBehavior(new EnemyBehavior(
            EnemyStrategyLibrary.forName("PROTOSS_4GATE"), game.enemy, new TechTree()));
        game.reset();

        // Warmup — advance past PROTOSS_4GATE first-attack trigger (~tick 100)
        for (int i = 0; i < WARMUP_TICKS; i++) {
            game.setUnitSpeed(1.0);
            game.tick();
            game.observeVisibility();
        }

        long[] tickNs = new long[MEASURE_TICKS];
        for (int i = 0; i < MEASURE_TICKS; i++) {
            long start = System.nanoTime();
            game.setUnitSpeed(1.0);
            game.tick();
            game.observeVisibility();
            tickNs[i] = System.nanoTime() - start;
        }

        String report = formatReport(tickNs);
        System.out.println(report);

        Path out = Path.of("target/emulated-benchmark-results.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Written to: " + out.toAbsolutePath());
    }

    private static String formatReport(long[] tickNs) {
        long[] tickUs = Arrays.stream(tickNs).map(ns -> ns / 1_000).toArray();
        long meanUs   = mean(tickUs);
        long p95Us    = p95(tickUs);
        long maxUs    = max(tickUs);
        long totalUs  = LongStream.of(tickUs).sum();
        long totalNs  = LongStream.of(tickNs).sum();
        double tps    = totalNs > 0 ? (MEASURE_TICKS * 1_000_000_000.0 / totalNs) : Double.POSITIVE_INFINITY;

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return String.format("""
                QuarkMind EmulatedGame Throughput Benchmark
                ──────────────────────────────────────────────────────
                Date:    %s
                Setup:   PROTOSS_4GATE enemy | PROTOSS player | emulatedMap() | A* pathfinding
                Warmup:  %d ticks (past PROTOSS_4GATE first attack at ~tick 100)
                Samples: %d ticks (steady-state combat ~tick 120-170)
                ──────────────────────────────────────────────────────
                Full engine tick (setUnitSpeed + tick + observeVisibility):
                  mean     p95      max
                  %4dµs   %4dµs   %4dµs
                ────────────────────────────────────────
                Throughput: %.1f ticks/sec
                ──────────────────────────────────────────────────────
                SC2 real-game ceiling: 22Hz (45ms/tick)
                Configured agent tick: 500ms  P99 budget: 400ms
                ──────────────────────────────────────────────────────
                Raw samples (µs): %s
                ──────────────────────────────────────────────────────
                Copy into docs/benchmarks/YYYY-MM-DD-l7-emulated.md
                Run: mvn test -Pbenchmark -Dtest=EmulatedGameBenchmarkTest
                """,
            ts, WARMUP_TICKS, MEASURE_TICKS,
            meanUs, p95Us, maxUs,
            tps,
            Arrays.toString(tickUs));
    }

    private static long mean(long[] a) { return LongStream.of(a).sum() / a.length; }
    private static long p95(long[] a) {
        long[] s = Arrays.stream(a).sorted().toArray();
        return s[Math.min((int) (a.length * 0.95), a.length - 1)];
    }
    private static long max(long[] a) { return LongStream.of(a).max().orElse(0); }
}
