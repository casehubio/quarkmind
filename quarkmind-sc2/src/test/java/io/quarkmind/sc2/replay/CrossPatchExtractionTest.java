package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("report")
class CrossPatchExtractionTest {

    record DatasetSource(String name, Path dir) {}

    private static List<DatasetSource> discoverDatasets() {
        var replayPacks = Path.of("../quarkmind-classifier/data/replay_packs");
        var datasets = new ArrayList<DatasetSource>();
        datasets.add(new DatasetSource("AI Arena (4.9.3)", Path.of("replays/aiarena_protoss")));
        datasets.add(new DatasetSource("Oracle (4.9.3 restored)", replayPacks.resolve("blizzard_ladder/4.9.3_oracle/restored")));
        datasets.add(new DatasetSource("IEM PyeongChang 2018", replayPacks.resolve("2018_IEM_PyeongChang")));
        datasets.add(new DatasetSource("ASUS ROG 2020", replayPacks.resolve("2020_ASUS_ROG_Online")));
        datasets.add(new DatasetSource("DreamHack Dallas 2025", replayPacks.resolve("2025_DreamHack_Dallas")));
        datasets.add(new DatasetSource("EWC 2025", replayPacks.resolve("2025_Esports_World_Cup")));
        datasets.add(new DatasetSource("FEL Cracow 2025", replayPacks.resolve("2025_FEL_Cracow")));
        datasets.add(new DatasetSource("HSC XXVII 2025", replayPacks.resolve("2025_HomeStory_Cup_XXVII")));
        datasets.add(new DatasetSource("HSC XXVIII 2026", replayPacks.resolve("2026_HomeStory_Cup_XXVIII")));
        datasets.add(new DatasetSource("HSC XXIX 2026", replayPacks.resolve("2026_HomeStory_Cup_XXIX")));
        return datasets;
    }

    static boolean anyDatasetExists() {
        return discoverDatasets().stream().anyMatch(ds -> Files.isDirectory(ds.dir()));
    }

    @Test
    @EnabledIf("anyDatasetExists")
    void trackerExtractionAcrossPatchVersions() throws Exception {
        var extractor = new TrackerEventFeatureExtractor();
        var buildStats = new TreeMap<Integer, BuildStats>();
        int totalProcessed = 0, totalFailed = 0;

        System.out.println("\n=== Cross-Patch Tracker Extraction Validation ===\n");

        for (var ds : discoverDatasets()) {
            if (!Files.isDirectory(ds.dir())) {
                System.out.printf("SKIP (not found): %s%n", ds.name());
                continue;
            }

            List<Path> replays;
            try (var s = Files.list(ds.dir())) {
                replays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
            }

            int dsProcessed = 0, dsFailed = 0, dsNoTracker = 0;

            for (Path replayPath : replays) {
                Replay replay;
                try {
                    replay = RepParserEngine.parseReplay(replayPath,
                        EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                } catch (Exception e) {
                    dsFailed++;
                    totalFailed++;
                    continue;
                }

                if (replay == null || replay.trackerEvents == null) {
                    dsNoTracker++;
                    continue;
                }

                int baseBuild = replay.header.baseBuild != null ? replay.header.baseBuild : 0;

                try {
                    Map<String, Object> result = extractor.extract(replay);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("trackerEvents");

                    int unitBorn = 0, unitInit = 0, upgrade = 0, playerStats = 0;
                    for (Map<String, Object> e : events) {
                        switch ((String) e.get("evtTypeName")) {
                            case "UnitBorn" -> unitBorn++;
                            case "UnitInit" -> unitInit++;
                            case "Upgrade" -> upgrade++;
                            case "PlayerStats" -> playerStats++;
                        }
                    }

                    var stats = buildStats.computeIfAbsent(baseBuild, k -> new BuildStats(ds.name()));
                    stats.replays++;
                    stats.unitBorn += unitBorn;
                    stats.unitInit += unitInit;
                    stats.upgrade += upgrade;
                    stats.playerStats += playerStats;

                    dsProcessed++;
                    totalProcessed++;
                } catch (Exception e) {
                    dsFailed++;
                    totalFailed++;
                    System.err.printf("  EXTRACT FAIL %s: %s%n", replayPath.getFileName(), e.getMessage());
                }
            }

            System.out.printf("%-30s replays=%d processed=%d noTracker=%d failed=%d%n",
                ds.name(), replays.size(), dsProcessed, dsNoTracker, dsFailed);
        }

        System.out.println("\n--- Per baseBuild Stats ---");
        System.out.printf("%-12s %-30s %6s %8s %8s %8s %8s%n",
            "baseBuild", "Source", "Reps", "UnitBorn", "UnitInit", "Upgrade", "PStats");
        System.out.println("-".repeat(95));

        for (var entry : buildStats.entrySet()) {
            var s = entry.getValue();
            System.out.printf("%-12d %-30s %6d %8d %8d %8d %8d%n",
                entry.getKey(), s.source, s.replays, s.unitBorn, s.unitInit, s.upgrade, s.playerStats);
        }

        System.out.printf("%nPatch versions covered: %d distinct baseBuild values%n", buildStats.size());
        System.out.printf("Total: %d processed, %d failed%n", totalProcessed, totalFailed);
        System.out.printf("Verdict: %s%n",
            totalFailed == 0 ? "PASS — extraction succeeded for all replays with tracker events"
                : totalFailed + " extraction failures");

        assertThat(totalProcessed).as("processed replays").isGreaterThan(0);
        assertThat(buildStats.size()).as("distinct baseBuild versions").isGreaterThanOrEqualTo(2);
    }

    static class BuildStats {
        final String source;
        int replays;
        int unitBorn, unitInit, upgrade, playerStats;
        BuildStats(String source) { this.source = source; }
    }
}
