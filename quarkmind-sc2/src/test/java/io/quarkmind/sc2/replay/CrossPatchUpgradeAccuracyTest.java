package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import io.quarkmind.domain.UpgradeType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-baseBuild upgrade detection accuracy. Validates AbilityProfile/AbilityMapping
 * against all available replay datasets, reporting accuracy broken down by patch era.
 * This is the key diagnostic for #368 — identifies which patch eras need new profiles.
 */
@Tag("report")
class CrossPatchUpgradeAccuracyTest {

    private static final Path REPLAY_PACKS = Path.of("../quarkmind-classifier/data/replay_packs");
    private static final Path ORACLE_RESTORED = REPLAY_PACKS.resolve("blizzard_ladder/4.9.3_oracle/restored");
    private static final Path AIARENA = Path.of("replays/aiarena_protoss");

    record DatasetSource(String name, Path dir) {}

    private static List<DatasetSource> allDatasets() {
        var ds = new ArrayList<DatasetSource>();
        ds.add(new DatasetSource("AI Arena (4.9.3)", AIARENA));
        ds.add(new DatasetSource("Oracle (4.9.3 restored)", ORACLE_RESTORED));
        ds.add(new DatasetSource("IEM PyeongChang 2018", REPLAY_PACKS.resolve("2018_IEM_PyeongChang")));
        ds.add(new DatasetSource("ASUS ROG 2020", REPLAY_PACKS.resolve("2020_ASUS_ROG_Online")));
        ds.add(new DatasetSource("DreamHack Dallas 2025", REPLAY_PACKS.resolve("2025_DreamHack_Dallas")));
        ds.add(new DatasetSource("EWC 2025", REPLAY_PACKS.resolve("2025_Esports_World_Cup")));
        ds.add(new DatasetSource("FEL Cracow 2025", REPLAY_PACKS.resolve("2025_FEL_Cracow")));
        ds.add(new DatasetSource("HSC XXVII 2025", REPLAY_PACKS.resolve("2025_HomeStory_Cup_XXVII")));
        ds.add(new DatasetSource("HSC XXVIII 2026", REPLAY_PACKS.resolve("2026_HomeStory_Cup_XXVIII")));
        ds.add(new DatasetSource("HSC XXIX 2026", REPLAY_PACKS.resolve("2026_HomeStory_Cup_XXIX")));
        return ds;
    }

    static boolean anyDatasetExists() {
        return allDatasets().stream().anyMatch(ds -> Files.isDirectory(ds.dir()));
    }

    @Test
    @EnabledIf("anyDatasetExists")
    void perBaseBuildUpgradeAccuracy() throws Exception {
        Set<String> trackedUpgrades = new HashSet<>();
        for (var ut : UpgradeType.values()) {
            trackedUpgrades.add(ut.pythonName());
        }

        // baseBuild → (upgrade → [oracle, detected])
        Map<Integer, Map<String, int[]>> perBuildPerUpgrade = new TreeMap<>();
        Map<Integer, String> buildToSource = new TreeMap<>();
        Map<Integer, int[]> perBuildTotals = new TreeMap<>();
        int totalReplays = 0, skippedNoEvents = 0;

        for (var ds : allDatasets()) {
            if (!Files.isDirectory(ds.dir())) continue;

            List<Path> replays;
            try (var s = Files.list(ds.dir())) {
                replays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
            }

            for (Path rp : replays) {
                Replay rep;
                try {
                    rep = RepParserEngine.parseReplay(rp,
                        EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
                } catch (Exception e) { continue; }
                if (rep == null || rep.details == null) continue;
                if (rep.trackerEvents == null || rep.gameEvents == null) {
                    skippedNoEvents++;
                    continue;
                }

                int baseBuild = rep.header != null && rep.header.baseBuild != null
                    ? rep.header.baseBuild : 0;
                buildToSource.putIfAbsent(baseBuild, ds.name());
                totalReplays++;

                var players = rep.details.getPlayerList();
                Map<Integer, Race> playerRaces = new HashMap<>();
                for (int i = 0; i < players.length; i++) {
                    if (players[i].getRace() != null) playerRaces.put(i, players[i].getRace());
                }

                AbilityProfile profile = AbilityProfile.resolve(baseBuild);
                Map<Integer, AbilityMapping> mappings = new HashMap<>();
                for (var entry : playerRaces.entrySet()) {
                    mappings.put(entry.getKey(),
                        new AbilityMapping(entry.getKey() + 1, true, entry.getValue(), profile));
                }

                Map<Integer, Set<String>> detected = new HashMap<>();
                for (var raw : rep.gameEvents.getEvents()) {
                    if (raw instanceof SelectionDeltaEvent sel) {
                        for (var m : mappings.values()) m.onSelection(sel);
                    } else if (raw instanceof CmdEvent cmd) {
                        for (var m : mappings.values()) {
                            for (var result : m.process(cmd)) {
                                if (result instanceof ReplayCommand.UpgradeCommand uc) {
                                    detected.computeIfAbsent(cmd.getUserId(), k -> new HashSet<>())
                                        .add(uc.upgradeName());
                                }
                            }
                        }
                    }
                }

                for (var raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                    IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                    if (upgrade.getPlayerId() == null) continue;
                    String name = upgrade.getUpgradeTypeName().toString();
                    if (!trackedUpgrades.contains(name)) continue;

                    int userId = upgrade.getPlayerId() - 1;
                    var buildUpgrades = perBuildPerUpgrade.computeIfAbsent(baseBuild, k -> new TreeMap<>());
                    int[] counts = buildUpgrades.computeIfAbsent(name, k -> new int[2]);
                    counts[0]++;

                    int[] totals = perBuildTotals.computeIfAbsent(baseBuild, k -> new int[2]);
                    totals[0]++;

                    if (detected.getOrDefault(userId, Set.of()).contains(name)) {
                        counts[1]++;
                        totals[1]++;
                    }
                }
            }
        }

        System.out.printf("%n=== Cross-Patch Upgrade Detection Accuracy ===%n");
        System.out.printf("  Replays processed: %d  (skipped no game/tracker events: %d)%n%n", totalReplays, skippedNoEvents);

        for (var buildEntry : perBuildTotals.entrySet()) {
            int baseBuild = buildEntry.getKey();
            int[] totals = buildEntry.getValue();
            double accuracy = totals[0] > 0 ? 100.0 * totals[1] / totals[0] : 0;
            String source = buildToSource.getOrDefault(baseBuild, "?");
            AbilityProfile profile = AbilityProfile.resolve(baseBuild);

            System.out.printf("  baseBuild=%-8d %-30s profile=%-10s  %d/%d (%.1f%%)%n",
                baseBuild, source, profile.name(), totals[1], totals[0], accuracy);

            var upgrades = perBuildPerUpgrade.getOrDefault(baseBuild, Map.of());
            List<Map.Entry<String, int[]>> missed = upgrades.entrySet().stream()
                .filter(e -> e.getValue()[1] < e.getValue()[0])
                .sorted((a, b) -> (b.getValue()[0] - b.getValue()[1]) - (a.getValue()[0] - a.getValue()[1]))
                .toList();

            if (!missed.isEmpty()) {
                System.out.println("    Missed upgrades:");
                for (var m : missed) {
                    System.out.printf("      %-40s %d/%d (%.0f%%)%n",
                        m.getKey(), m.getValue()[1], m.getValue()[0],
                        100.0 * m.getValue()[1] / m.getValue()[0]);
                }
            }
            System.out.println();
        }

        int totalOracle = perBuildTotals.values().stream().mapToInt(t -> t[0]).sum();
        int totalDetected = perBuildTotals.values().stream().mapToInt(t -> t[1]).sum();
        double overallAccuracy = totalOracle > 0 ? 100.0 * totalDetected / totalOracle : 0;
        System.out.printf("  Overall: %d/%d (%.1f%%)%n", totalDetected, totalOracle, overallAccuracy);

        assertThat(totalReplays).as("processed replays").isGreaterThan(0);
    }
}
