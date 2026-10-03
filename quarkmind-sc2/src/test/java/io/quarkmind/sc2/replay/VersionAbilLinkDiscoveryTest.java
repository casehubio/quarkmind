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

@Tag("diagnostic")
class VersionAbilLinkDiscoveryTest {

    private static final Path ORACLE_RESTORED = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path DATA_ROOT = Path.of("../quarkmind-classifier/data/replay_packs");
    private static final Path AIARENA_DIR = Path.of("replays/aiarena_protoss");

    static boolean oracleExists() {
        return Files.isDirectory(ORACLE_RESTORED);
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverBaseBuildRanges() throws Exception {
        Map<String, List<Integer>> datasetBuilds = new TreeMap<>();

        scanDataset("oracle-4.9.3", ORACLE_RESTORED, datasetBuilds);

        if (Files.isDirectory(AIARENA_DIR)) {
            scanDataset("aiarena-protoss", AIARENA_DIR, datasetBuilds);
        }

        for (String d : List.of("2025_HomeStory_Cup_XXVII", "2026_HomeStory_Cup_XXVIII", "2026_HomeStory_Cup_XXIX")) {
            Path dp = DATA_ROOT.resolve(d);
            if (Files.isDirectory(dp)) {
                scanDataset(d, dp, datasetBuilds);
            }
        }

        System.out.printf("%n=== baseBuild Ranges by Dataset ===%n");
        for (var entry : datasetBuilds.entrySet()) {
            var builds = entry.getValue().stream().sorted().toList();
            if (builds.isEmpty()) { continue; }
            int min = builds.getFirst();
            int max = builds.getLast();
            long distinct = builds.stream().distinct().count();
            System.out.printf("  %-35s replays=%3d  min=%d  max=%d  distinct=%d  values=%s%n",
                    entry.getKey(), builds.size(), min, max, distinct,
                    builds.stream().distinct().sorted().toList());
        }
    }

    private void scanDataset(String name, Path root, Map<String, List<Integer>> results) throws Exception {
        List<Integer> builds = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(p -> {
                try {
                    Replay rep = RepParserEngine.parseReplay(p, EnumSet.of(RepContent.INIT_DATA));
                    if (rep != null && rep.header != null && rep.header.baseBuild != null) {
                        builds.add(rep.header.baseBuild);
                    }
                } catch (Exception ignored) {}
            });
        }
        results.put(name, builds);
    }
}
