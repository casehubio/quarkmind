package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Bulk feature extraction from stripped replays.
 * Processes all .SC2Replay files in an input directory, writes game_json
 * to output directory. Crash-resume: skips replays with existing output.
 *
 * Usage: java BulkFeatureExtractor &lt;inputDir&gt; &lt;outputDir&gt; [threads]
 */
public class BulkFeatureExtractor {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BulkFeatureExtractor <inputDir> <outputDir> [threads]");
            System.exit(1);
        }

        Path inputDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;

        if (!Files.isDirectory(inputDir)) {
            System.err.println("Input directory does not exist: " + inputDir);
            System.exit(1);
        }
        Files.createDirectories(outputDir);

        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long totalReplays;
        try (Stream<Path> countStream = Files.list(inputDir)) {
            totalReplays = countStream.filter(p -> p.toString().endsWith(".SC2Replay")).count();
        }

        System.out.printf("Processing %d replays with %d threads...%n", totalReplays, threads);
        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            try (Stream<Path> replayStream = Files.list(inputDir)) {
                for (Path replay : replayStream
                        .filter(p -> p.toString().endsWith(".SC2Replay"))
                        .sorted()
                        .toList()) {

                    String baseName = replay.getFileName().toString().replace(".SC2Replay", ".json");
                    Path outputFile = outputDir.resolve(baseName);

                    if (Files.exists(outputFile)) {
                        skipped.incrementAndGet();
                        continue;
                    }

                    executor.submit(() -> {
                        try {
                            var extractor = new ReplayFeatureExtractor();
                            Map<String, Object> gameJson = extractor.extract(replay);
                            Path tmpFile = outputDir.resolve(baseName + ".tmp");
                            mapper.writeValue(tmpFile.toFile(), gameJson);
                            Files.move(tmpFile, outputFile, StandardCopyOption.ATOMIC_MOVE);
                            int count = processed.incrementAndGet();
                            if (count % 1000 == 0) {
                                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                                System.out.printf("  %d/%d processed (%.1f/s)%n",
                                    count, totalReplays, count / elapsed);
                            }
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            System.err.printf("FAIL %s: %s%n", replay.getFileName(), e.getMessage());
                        }
                    });
                }
            }

            executor.shutdown();
            executor.awaitTermination(24, TimeUnit.HOURS);
        } finally {
            executor.shutdownNow();
        }

        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        System.out.printf("%nDone in %.1fs — processed=%d skipped=%d failed=%d (%.1f replays/s)%n",
            elapsed, processed.get(), skipped.get(), failed.get(),
            processed.get() / elapsed);
    }
}
