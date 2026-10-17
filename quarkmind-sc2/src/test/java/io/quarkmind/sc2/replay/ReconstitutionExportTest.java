package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("report")
class ReconstitutionExportTest {

    private static final Path CLASSIFIER_DATA = Path.of("../quarkmind-classifier/data/reconstituted");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record DatasetSource(String name, Path dir) {}

    private static List<DatasetSource> discoverDatasets() {
        var replayPacks = Path.of("../quarkmind-classifier/data/replay_packs");
        var datasets = new ArrayList<DatasetSource>();
        datasets.add(new DatasetSource("blizzard_ladder_4.9.3", replayPacks.resolve("blizzard_ladder/4.9.3/replays")));
        datasets.add(new DatasetSource("blizzard_ladder_4.9.2", replayPacks.resolve("blizzard_ladder/4.9.2/replays")));
        datasets.add(new DatasetSource("blizzard_ladder_4.9.1", replayPacks.resolve("blizzard_ladder/4.9.1/replays")));
        datasets.add(new DatasetSource("blizzard_ladder_4.10.1", replayPacks.resolve("blizzard_ladder/4.10.1/replays")));
        datasets.add(new DatasetSource("hsc_2025", replayPacks.resolve("2025_HomeStory_Cup_XXVII")));
        datasets.add(new DatasetSource("iem_pyeongchang_2018", replayPacks.resolve("2018_IEM_PyeongChang")));
        datasets.add(new DatasetSource("asus_rog_2020", replayPacks.resolve("2020_ASUS_ROG_Online")));
        datasets.add(new DatasetSource("dreamhack_dallas_2025", replayPacks.resolve("2025_DreamHack_Dallas")));
        datasets.add(new DatasetSource("ewc_2025", replayPacks.resolve("2025_Esports_World_Cup")));
        datasets.add(new DatasetSource("fel_cracow_2025", replayPacks.resolve("2025_FEL_Cracow")));
        datasets.add(new DatasetSource("hsc_xxviii_2026", replayPacks.resolve("2026_HomeStory_Cup_XXVIII")));
        datasets.add(new DatasetSource("hsc_xxix_2026", replayPacks.resolve("2026_HomeStory_Cup_XXIX")));
        return datasets;
    }

    @Test
    void exportAllDatasets() throws Exception {
        var datasetFilter = System.getProperty("recon.dataset", "");
        int offset = Integer.parseInt(System.getProperty("recon.offset", "0"));
        int limit = Integer.parseInt(System.getProperty("recon.limit", "0"));
        boolean skipExisting = Boolean.parseBoolean(System.getProperty("recon.skip-existing", "false"));

        var extractor = new ReplayFeatureExtractor();
        int total = 0, success = 0, failed = 0, skipped = 0;
        String gitSha = getGitSha();

        for (var ds : discoverDatasets()) {
            if (!datasetFilter.isEmpty() && !ds.name().equals(datasetFilter)) {
                continue;
            }
            if (!Files.isDirectory(ds.dir())) {
                System.out.printf("SKIP (not found): %s at %s%n", ds.name(), ds.dir());
                continue;
            }
            var outDir = CLASSIFIER_DATA.resolve(ds.name());
            Files.createDirectories(outDir);

            List<Path> replays;
            try (Stream<Path> walk = Files.walk(ds.dir())) {
                replays = walk.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
            }

            int start = Math.min(offset, replays.size());
            int end = limit > 0 ? Math.min(start + limit, replays.size()) : replays.size();
            var batch = replays.subList(start, end);

            System.out.printf("  %s: %d replays found, processing [%d..%d)%n", ds.name(), replays.size(), start, end);
            long t0 = System.currentTimeMillis();

            for (var replayPath : batch) {
                total++;
                try {
                    var hash = md5(replayPath.getFileName().toString());
                    var outFile = outDir.resolve(hash + ".json");
                    if (skipExisting && Files.exists(outFile)) {
                        skipped++;
                        continue;
                    }

                    var gameJson = extractor.extract(replayPath);

                    @SuppressWarnings("unchecked")
                    var header = (Map<String, Object>) gameJson.getOrDefault("header", Map.of());
                    int baseBuild = header.containsKey("baseBuild") ? (int) header.get("baseBuild") : 0;
                    String abilityProfile = AbilityProfile.resolve(baseBuild > 0 ? baseBuild : 75689).name();

                    var reconMeta = new LinkedHashMap<String, Object>();
                    reconMeta.put("baseBuild", baseBuild);
                    reconMeta.put("abilityProfile", abilityProfile);
                    reconMeta.put("datasetSource", ds.name());
                    reconMeta.put("extractorVersion", gitSha);
                    gameJson.put("reconstitution", reconMeta);

                    MAPPER.writeValue(outFile.toFile(), gameJson);
                    success++;
                } catch (Exception e) {
                    System.err.printf("FAIL: %s — %s%n", replayPath.getFileName(), e.getMessage());
                    failed++;
                }
                if (total % 10000 == 0) {
                    long elapsed = System.currentTimeMillis() - t0;
                    double rate = total / (elapsed / 1000.0);
                    System.out.printf("    progress: %d processed, %.0f replays/sec%n", total, rate);
                }
            }

            long elapsed = System.currentTimeMillis() - t0;
            System.out.printf("  %s done in %.1fs (%.0f replays/sec)%n",
                    ds.name(), elapsed / 1000.0, total > 0 ? total / (elapsed / 1000.0) : 0);
        }
        System.out.printf("%nReconstitution complete: %d total, %d success, %d skipped, %d failed%n",
                total, success, skipped, failed);
        assertTrue(success + skipped > 0, "No replays reconstituted successfully");
    }

    private static String md5(String input) throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance("MD5");
        var bytes = md.digest(input.getBytes());
        var sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String getGitSha() {
        try {
            var proc = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            return new String(proc.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
