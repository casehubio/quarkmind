package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
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

@Tag("diagnostic")
class CrossPatchUpgradeDiagnosticTest {

    private static final Path HSC_XXVII_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2025_HomeStory_Cup_XXVII");

    private static final List<String> COSMETIC_PREFIXES = List.of("RewardDance", "Spray", "GhostAlternate");

    static boolean hscExists() {
        if (!Files.isDirectory(HSC_XXVII_DIR)) return false;
        try (var stream = Files.walk(HSC_XXVII_DIR, 3)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("hscExists")
    void correlateUpgradesWithCmdEvents() throws Exception {
        Map<String, Map<String, Integer>> upgradeToAbilFreq = new TreeMap<>();
        int totalReplays = 0;
        int totalUpgrades = 0;
        int matchedUpgrades = 0;

        List<Path> replays;
        try (var stream = Files.walk(HSC_XXVII_DIR, 3)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        for (Path replayPath : replays) {
            Replay trackerRep = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.TRACKER_EVENTS));
            if (trackerRep == null || trackerRep.trackerEvents == null) continue;

            List<Event> gameEvents;
            try { gameEvents = GameEventStream.events(replayPath); } catch (Exception e) { continue; }

            totalReplays++;

            for (Event raw : trackerRep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                if (upgrade.getPlayerId() == null) continue;
                String name = upgrade.getUpgradeTypeName().toString();
                if (COSMETIC_PREFIXES.stream().anyMatch(name::startsWith)) continue;
                if ("GameHeartActive".equals(name)) continue;

                totalUpgrades++;
                int userId = upgrade.getPlayerId() - 1;
                long completionLoop = upgrade.getLoop();

                CmdEvent bestMatch = null;
                long bestDist = Long.MAX_VALUE;
                int bestSelSize = 0;
                int bestUnitLink = -1;

                SelectionDeltaEvent lastSelection = null;
                int selectionSize = 0;

                for (Event ge : gameEvents) {
                    if (ge instanceof SelectionDeltaEvent sd && sd.getUserId() == userId) {
                        lastSelection = sd;
                    }
                    if (!(ge instanceof CmdEvent cmd)) continue;
                    if (cmd.getUserId() != userId) continue;

                    long dist = completionLoop - cmd.getLoop();
                    if (dist < 0 || dist > 5000) continue;
                    if (cmd.getTargetPoint() != null || cmd.getTargetUnit() != null) continue;

                    Integer abilLink = cmd.getAbilLink();
                    if (abilLink == null) continue;

                    if (dist < bestDist) {
                        bestDist = dist;
                        bestMatch = cmd;
                    }
                }

                if (bestMatch != null) {
                    matchedUpgrades++;
                    Integer abilLink = bestMatch.getAbilLink();
                    Integer idx = bestMatch.getAbilCmdIndex();
                    String key = String.format("abilLink=%d idx=%d", abilLink, idx != null ? idx : 0);
                    upgradeToAbilFreq.computeIfAbsent(name, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
                }
            }
        }

        System.out.printf("%n=== Cross-Patch Upgrade↔CmdEvent Correlation (HSC XXVII, %d replays) ===%n", totalReplays);
        System.out.printf("Total upgrades: %d, matched: %d (%.1f%%)%n%n", totalUpgrades, matchedUpgrades,
            totalUpgrades > 0 ? 100.0 * matchedUpgrades / totalUpgrades : 0);

        System.out.printf("%-40s %-30s %5s%n", "Upgrade", "Best CmdEvent", "Count");
        System.out.println("-".repeat(80));
        for (var entry : upgradeToAbilFreq.entrySet()) {
            String upgradeName = entry.getKey();
            Map<String, Integer> freq = entry.getValue();
            String best = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> String.format("%s (×%d)", e.getKey(), e.getValue()))
                .orElse("?");
            int total = freq.values().stream().mapToInt(i -> i).sum();
            System.out.printf("%-40s %-30s %5d%n", upgradeName, best, total);
            if (freq.size() > 1) {
                for (var f : freq.entrySet()) {
                    System.out.printf("  %-38s %-30s %5d%n", "", f.getKey(), f.getValue());
                }
            }
        }
    }

    @Test
    @EnabledIf("hscExists")
    void correlateProtossUpgradesWithAbilLink176() throws Exception {
        Map<String, Map<Integer, Integer>> upgradeToIdxFreq = new TreeMap<>();
        int totalProtossUpgrades = 0;
        int matchedWith176 = 0;

        List<Path> replays;
        try (var stream = Files.walk(HSC_XXVII_DIR, 3)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        for (Path replayPath : replays) {
            Replay trackerRep = RepParserEngine.parseReplay(replayPath,
                EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            if (trackerRep == null || trackerRep.trackerEvents == null) continue;

            List<Event> gameEvents;
            try { gameEvents = GameEventStream.events(replayPath); } catch (Exception e) { continue; }

            var players = trackerRep.details.getPlayerList();

            for (Event raw : trackerRep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                if (upgrade.getPlayerId() == null || upgrade.getPlayerId() == 0) continue;
                String name = upgrade.getUpgradeTypeName().toString();
                if (COSMETIC_PREFIXES.stream().anyMatch(name::startsWith)) continue;
                if ("GameHeartActive".equals(name)) continue;

                int pid = upgrade.getPlayerId();
                if (pid > players.length) continue;
                var race = players[pid - 1].getRace();
                if (race != hu.scelight.sc2.rep.model.details.Race.PROTOSS) continue;

                totalProtossUpgrades++;
                int userId = pid - 1;
                long completionLoop = upgrade.getLoop();

                for (Event ge : gameEvents) {
                    if (!(ge instanceof CmdEvent cmd)) continue;
                    if (cmd.getUserId() != userId) continue;
                    long dist = completionLoop - cmd.getLoop();
                    if (dist < 0 || dist > 5000) continue;
                    if (cmd.getAbilLink() == null || cmd.getAbilLink() != 176) continue;

                    matchedWith176++;
                    Integer idx = cmd.getAbilCmdIndex();
                    upgradeToIdxFreq.computeIfAbsent(name, k -> new TreeMap<>())
                        .merge(idx != null ? idx : 0, 1, Integer::sum);
                    break;
                }
            }
        }

        System.out.printf("%n=== abilLink=176 Protoss Upgrade Correlation (HSC XXVII) ===%n");
        System.out.printf("Total Protoss upgrades: %d, matched with 176: %d (%.1f%%)%n%n",
            totalProtossUpgrades, matchedWith176,
            totalProtossUpgrades > 0 ? 100.0 * matchedWith176 / totalProtossUpgrades : 0);

        System.out.printf("%-40s %s%n", "Upgrade", "idx → count");
        System.out.println("-".repeat(60));
        for (var entry : upgradeToIdxFreq.entrySet()) {
            System.out.printf("%-40s %s%n", entry.getKey(), entry.getValue());
        }
    }

    @Test
    @EnabledIf("hscExists")
    void discoverAbilLinksByCooccurrence() throws Exception {
        List<Path> replays;
        try (var stream = Files.walk(HSC_XXVII_DIR, 3)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        Map<String, Set<String>> upgradeToReplays = new TreeMap<>();
        Map<String, Map<String, Set<String>>> abilToUpgradeReplays = new HashMap<>();
        Set<String> allReplays = new HashSet<>();

        for (Path replayPath : replays) {
            String replayId = replayPath.getFileName().toString();
            allReplays.add(replayId);

            Replay trackerRep = RepParserEngine.parseReplay(replayPath,
                EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            if (trackerRep == null || trackerRep.trackerEvents == null) continue;

            List<Event> gameEvents;
            try { gameEvents = GameEventStream.events(replayPath); } catch (Exception e) { continue; }

            var players = trackerRep.details.getPlayerList();

            Set<String> upgradesInReplay = new HashSet<>();
            for (Event raw : trackerRep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                if (upgrade.getPlayerId() == null || upgrade.getPlayerId() == 0) continue;
                String name = upgrade.getUpgradeTypeName().toString();
                if (COSMETIC_PREFIXES.stream().anyMatch(name::startsWith)) continue;
                if ("GameHeartActive".equals(name)) continue;
                int pid = upgrade.getPlayerId();
                if (pid > players.length) continue;
                String race = players[pid - 1].getRace().toString().substring(0, 1);
                String key = race + ":" + name;
                upgradesInReplay.add(key);
                upgradeToReplays.computeIfAbsent(key, k -> new HashSet<>()).add(replayId);
            }

            Set<String> noTargetAbils = new HashSet<>();
            for (Event ge : gameEvents) {
                if (!(ge instanceof CmdEvent cmd)) continue;
                if (cmd.getTargetPoint() != null || cmd.getTargetUnit() != null) continue;
                Integer abilLink = cmd.getAbilLink();
                if (abilLink == null) continue;
                int userId = cmd.getUserId();
                if (userId < 0 || userId >= players.length) continue;
                String race = players[userId].getRace().toString().substring(0, 1);
                Integer idx = cmd.getAbilCmdIndex();
                String abilKey = race + ":abilLink=" + abilLink + "/idx=" + (idx != null ? idx : 0);
                noTargetAbils.add(abilKey);
            }

            for (String abilKey : noTargetAbils) {
                for (String upgradeKey : upgradesInReplay) {
                    if (!abilKey.substring(0, 2).equals(upgradeKey.substring(0, 2))) continue;
                    abilToUpgradeReplays
                        .computeIfAbsent(abilKey, k -> new HashMap<>())
                        .computeIfAbsent(upgradeKey, k -> new HashSet<>())
                        .add(replayId);
                }
            }
        }

        System.out.printf("%n=== Co-occurrence Analysis: abilLink×upgrade (HSC XXVII, %d replays) ===%n%n", allReplays.size());
        System.out.printf("%-30s %-20s %5s %5s %5s %7s %7s%n",
            "Upgrade", "abilLink/idx", "Both", "Upg", "Abil", "Prec", "Recall");
        System.out.println("-".repeat(100));

        for (var upgradeEntry : upgradeToReplays.entrySet()) {
            String upgradeKey = upgradeEntry.getKey();
            Set<String> upgradeReplays = upgradeEntry.getValue();
            int upgradeCount = upgradeReplays.size();

            List<String[]> candidates = new ArrayList<>();
            for (var abilEntry : abilToUpgradeReplays.entrySet()) {
                String abilKey = abilEntry.getKey();
                Map<String, Set<String>> upgradeMap = abilEntry.getValue();
                if (!upgradeMap.containsKey(upgradeKey)) continue;
                Set<String> cooccurReplays = upgradeMap.get(upgradeKey);
                int cooccur = cooccurReplays.size();
                long abilTotal = allReplays.stream().filter(r -> {
                    var m = abilToUpgradeReplays.get(abilKey);
                    return m != null && m.values().stream().anyMatch(s -> s.contains(r));
                }).count();
                double precision = abilTotal > 0 ? (double) cooccur / abilTotal : 0;
                double recall = upgradeCount > 0 ? (double) cooccur / upgradeCount : 0;
                if (recall >= 0.3 && precision >= 0.1) {
                    candidates.add(new String[]{abilKey, String.valueOf(cooccur), String.valueOf(upgradeCount),
                        String.valueOf(abilTotal), String.format("%.1f%%", precision * 100), String.format("%.1f%%", recall * 100)});
                }
            }

            candidates.sort((a, b) -> {
                double scoreA = Double.parseDouble(a[4].replace("%", "")) * Double.parseDouble(a[5].replace("%", ""));
                double scoreB = Double.parseDouble(b[4].replace("%", "")) * Double.parseDouble(b[5].replace("%", ""));
                return Double.compare(scoreB, scoreA);
            });

            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                String[] c = candidates.get(i);
                String label = i == 0 ? upgradeKey : "";
                System.out.printf("%-30s %-20s %5s %5s %5s %7s %7s%n",
                    label, c[0].substring(2), c[1], c[2], c[3], c[4], c[5]);
            }
        }
    }

    @Test
    void scanBaseBuildAcrossDatasets() throws Exception {
        Path packs = Path.of("../quarkmind-classifier/data/replay_packs");
        if (!Files.isDirectory(packs)) return;
        
        Map<String, Map<Integer, Integer>> datasetToBuilds = new TreeMap<>();
        
        try (var dirs = Files.list(packs)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String name = dir.getFileName().toString();
                if (name.equals("blizzard_ladder")) continue;
                
                Map<Integer, Integer> buildCounts = new TreeMap<>();
                List<Path> replays;
                try (var stream = Files.walk(dir, 4)) {
                    replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).toList();
                }
                
                for (Path replay : replays) {
                    try {
                        var rep = RepParserEngine.parseReplay(replay, EnumSet.noneOf(RepContent.class));
                        if (rep != null && rep.header != null && rep.header.baseBuild != null) {
                            buildCounts.merge(rep.header.baseBuild, 1, Integer::sum);
                        }
                    } catch (Exception e) { /* skip unparseable */ }
                }
                
                if (!buildCounts.isEmpty()) {
                    datasetToBuilds.put(name, buildCounts);
                }
            }
        }
        
        System.out.printf("%n=== baseBuild Survey Across Replay Datasets ===%n%n");
        System.out.printf("%-40s %10s %s%n", "Dataset", "Replays", "baseBuild(s)");
        System.out.println("-".repeat(80));
        for (var entry : datasetToBuilds.entrySet()) {
            int total = entry.getValue().values().stream().mapToInt(i -> i).sum();
            System.out.printf("%-40s %10d %s%n", entry.getKey(), total, entry.getValue());
        }
    }

    @Test
    @EnabledIf("hscExists")
    void dumpDetailedCmdEventsNearUpgrades() throws Exception {
        List<Path> replays;
        try (var stream = Files.walk(HSC_XXVII_DIR, 3)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        int count = 0;
        for (Path replayPath : replays) {
            if (count >= 5) break;

            Replay trackerRep = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.TRACKER_EVENTS));
            if (trackerRep == null || trackerRep.trackerEvents == null) continue;

            List<Event> gameEvents;
            try { gameEvents = GameEventStream.events(replayPath); } catch (Exception e) { continue; }

            boolean hasUpgrade = false;
            for (Event raw : trackerRep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                if (upgrade.getPlayerId() == null) continue;
                String name = upgrade.getUpgradeTypeName().toString();
                if (COSMETIC_PREFIXES.stream().anyMatch(name::startsWith)) continue;
                if ("GameHeartActive".equals(name)) continue;

                hasUpgrade = true;
                int userId = upgrade.getPlayerId() - 1;
                long completionLoop = upgrade.getLoop();

                System.out.printf("%n=== %s P%d completion=%d replay=%s ===%n",
                    name, upgrade.getPlayerId(), completionLoop,
                    replayPath.getFileName().toString().length() > 30
                        ? replayPath.getFileName().toString().substring(0, 30)
                        : replayPath.getFileName().toString());

                for (Event ge : gameEvents) {
                    if (!(ge instanceof CmdEvent cmd)) continue;
                    if (cmd.getUserId() != userId) continue;
                    long dist = completionLoop - cmd.getLoop();
                    if (dist < 0 || dist > 5000) continue;

                    Integer abilLink = cmd.getAbilLink();
                    Integer idx = cmd.getAbilCmdIndex();
                    boolean hasTP = cmd.getTargetPoint() != null;
                    boolean hasTU = cmd.getTargetUnit() != null;
                    System.out.printf("  loop=%6d dist=%5d abilLink=%-4s idx=%-3s hasTP=%s hasTU=%s%n",
                        cmd.getLoop(), dist,
                        abilLink != null ? abilLink : "null",
                        idx != null ? idx : "0",
                        hasTP, hasTU);
                }
            }
            if (hasUpgrade) count++;
        }
    }
}
