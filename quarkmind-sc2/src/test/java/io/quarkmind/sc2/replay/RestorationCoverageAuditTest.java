package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class RestorationCoverageAuditTest {

    record DatasetSource(String name, Path dir) {}

    record DatasetResult(String name, int total, int tracker, int stripped,
                         int failed, List<String> failures) {
        double coveragePct() {
            int valid = total - failed;
            return valid > 0 ? 100.0 * tracker / valid : 0;
        }
    }

    private static List<DatasetSource> discoverDatasets() {
        var replayPacks = Path.of("../quarkmind-classifier/data/replay_packs");
        var datasets = new ArrayList<DatasetSource>();
        datasets.add(new DatasetSource("blizzard_ladder/4.9.3",
            replayPacks.resolve("blizzard_ladder/4.9.3/replays")));
        datasets.add(new DatasetSource("blizzard_ladder/4.10.1",
            replayPacks.resolve("blizzard_ladder/4.10.1/replays")));
        datasets.add(new DatasetSource("blizzard_ladder/4.9.3_oracle",
            replayPacks.resolve("blizzard_ladder/4.9.3_oracle/restored")));
        datasets.add(new DatasetSource("blizzard_ladder/4.9.3_restored",
            replayPacks.resolve("blizzard_ladder/4.9.3_restored")));
        datasets.add(new DatasetSource("aiarena_protoss",
            Path.of("replays/aiarena_protoss")));
        datasets.add(new DatasetSource("2025_HomeStory_Cup_XXVII",
            replayPacks.resolve("2025_HomeStory_Cup_XXVII")));
        datasets.add(new DatasetSource("2018_IEM_PyeongChang",
            replayPacks.resolve("2018_IEM_PyeongChang")));
        datasets.add(new DatasetSource("2020_ASUS_ROG_Online",
            replayPacks.resolve("2020_ASUS_ROG_Online")));
        datasets.add(new DatasetSource("2025_DreamHack_Dallas",
            replayPacks.resolve("2025_DreamHack_Dallas")));
        datasets.add(new DatasetSource("2025_Esports_World_Cup",
            replayPacks.resolve("2025_Esports_World_Cup")));
        datasets.add(new DatasetSource("2025_FEL_Cracow",
            replayPacks.resolve("2025_FEL_Cracow")));
        datasets.add(new DatasetSource("2026_HomeStory_Cup_XXVIII",
            replayPacks.resolve("2026_HomeStory_Cup_XXVIII")));
        datasets.add(new DatasetSource("2026_HomeStory_Cup_XXIX",
            replayPacks.resolve("2026_HomeStory_Cup_XXIX")));
        return datasets;
    }

    static boolean anyDatasetExists() {
        return discoverDatasets().stream().anyMatch(ds -> Files.isDirectory(ds.dir()));
    }

    @Test
    @EnabledIf("anyDatasetExists")
    void restorationCoverageAudit() throws Exception {
        var results = new ArrayList<DatasetResult>();

        System.out.println("\n=== Restoration Coverage Audit ===\n");

        for (var ds : discoverDatasets()) {
            if (!Files.isDirectory(ds.dir())) {
                System.out.printf("SKIP (not found): %s%n", ds.name());
                continue;
            }

            List<Path> replays;
            try (Stream<Path> walk = Files.walk(ds.dir())) {
                replays = walk.filter(p -> p.toString().endsWith(".SC2Replay")).toList();
            }

            int tracker = 0, stripped = 0, failed = 0;
            var failures = new ArrayList<String>();

            for (Path replayPath : replays) {
                try {
                    Replay replay = RepParserEngine.parseReplay(replayPath,
                        EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                    if (replay != null && replay.trackerEvents != null
                            && replay.trackerEvents.getEvents().length > 0) {
                        tracker++;
                    } else {
                        stripped++;
                    }
                } catch (Exception e) {
                    failed++;
                    failures.add(replayPath.getFileName().toString() + ": " + e.getMessage());
                }
            }

            var result = new DatasetResult(ds.name(), replays.size(), tracker, stripped,
                failed, failures);
            results.add(result);

            System.out.printf("%-35s total=%d tracker=%d stripped=%d failed=%d (%.1f%%)%n",
                ds.name(), result.total(), result.tracker(), result.stripped(),
                result.failed(), result.coveragePct());
        }

        writeMarkdownReport(results);

        int totalScanned = results.stream().mapToInt(DatasetResult::total).sum();
        assertThat(totalScanned).as("total replays scanned").isGreaterThan(0);

        System.out.printf("%nReport written to docs/benchmarks/restoration-coverage.md%n");
    }

    private void writeMarkdownReport(List<DatasetResult> results) throws IOException {
        Path reportPath = Path.of("../docs/benchmarks/restoration-coverage.md");
        Files.createDirectories(reportPath.getParent());

        int totalAll = results.stream().mapToInt(DatasetResult::total).sum();
        int totalTracker = results.stream().mapToInt(DatasetResult::tracker).sum();
        int totalStripped = results.stream().mapToInt(DatasetResult::stripped).sum();
        int totalFailed = results.stream().mapToInt(DatasetResult::failed).sum();
        int totalValid = totalAll - totalFailed;
        double overallCoverage = totalValid > 0 ? 100.0 * totalTracker / totalValid : 0;

        try (var pw = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            pw.printf("# Restoration Coverage Report — %s%n%n", LocalDate.now());
            pw.printf("**Context:** Phase 2.5 reconstitution accuracy gate (#366)%n");
            pw.printf("**Test:** `RestorationCoverageAuditTest`%n");
            pw.printf("**Issue:** #380%n%n");

            pw.println("## Per-Dataset Coverage");
            pw.println();
            pw.println("| Dataset | Total | Tracker | Stripped | Failed | Coverage % |");
            pw.println("|---------|------:|--------:|---------:|-------:|-----------:|");

            for (var r : results) {
                pw.printf("| %s | %,d | %,d | %,d | %d | %.1f%% |%n",
                    r.name(), r.total(), r.tracker(), r.stripped(),
                    r.failed(), r.coveragePct());
            }

            pw.println();
            pw.println("## Special Categories");
            pw.println();
            pw.println("| Dataset | Status | Notes |");
            pw.println("|---------|--------|-------|");
            pw.println("| IEM10 Taipei 2016 | JSON format | 30 games, tracker events embedded, separate pipeline |");
            pw.println("| SC2EGSet | Not downloaded | ~17,930 tournament replays available as nested ZIPs |");
            pw.println();

            pw.println("## Summary");
            pw.println();
            pw.printf("- **Total scanned:** %,d replays across %d datasets%n", totalAll, results.size());
            pw.printf("- **Tracker path (ground-truth):** %,d (%.1f%%)%n", totalTracker, overallCoverage);
            pw.printf("- **Stripped fallback (88%%/79%%):** %,d (%.1f%%)%n",
                totalStripped, totalValid > 0 ? 100.0 * totalStripped / totalValid : 0);
            pw.printf("- **Parse failures:** %d%n", totalFailed);
            pw.printf("- **Not scanned:** ~17,960 replays (IEM10 JSON + SC2EGSet not downloaded)%n");
            pw.println();

            List<DatasetResult> withFailures = results.stream()
                .filter(r -> r.failed() > 0).toList();
            if (!withFailures.isEmpty()) {
                pw.println("## Parse Failures");
                pw.println();
                pw.println("| Dataset | File | Error |");
                pw.println("|---------|------|-------|");
                for (var r : withFailures) {
                    for (String f : r.failures()) {
                        int colonIdx = f.indexOf(": ");
                        String file = colonIdx > 0 ? f.substring(0, colonIdx) : f;
                        String error = colonIdx > 0 ? f.substring(colonIdx + 2) : "unknown";
                        pw.printf("| %s | %s | %s |%n", r.name(), file, error);
                    }
                }
            }
        }
    }
}
