package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Race;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Tag("diagnostic")
class TournamentAbilLinkDiscoveryTest {

    private static final Path DATA_ROOT = Path.of("../quarkmind-classifier/data/replay_packs");
    private static final int WINDOW = 200;

    static boolean tournamentExists() {
        return Files.isDirectory(DATA_ROOT.resolve("2025_HomeStory_Cup_XXVII"));
    }

    record CmdRecord(int userId, long loop, int abilLink, int abilCmdIndex, int selectionSize) {}

    @Test
    @EnabledIf("tournamentExists")
    void discoverTournamentUpgradeAbilLinks() throws Exception {
        Set<String> trackedUpgrades = new java.util.HashSet<>();
        for (var ut : io.quarkmind.domain.UpgradeType.values()) {
            trackedUpgrades.add(ut.pythonName());
        }

        List<Path> replays = new ArrayList<>();
        for (String d : List.of("2025_HomeStory_Cup_XXVII", "2026_HomeStory_Cup_XXVIII", "2026_HomeStory_Cup_XXIX")) {
            Path dp = DATA_ROOT.resolve(d);
            if (Files.isDirectory(dp)) {
                try (var w = Files.walk(dp)) { w.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(replays::add); }
            }
        }

        Map<String, Map<String, Integer>> allResults = new TreeMap<>();
        Map<String, Map<String, Integer>> singleSelResults = new TreeMap<>();

        for (Path rp : replays.stream().sorted().toList()) {
            Replay rep;
            try {
                rep = RepParserEngine.parseReplay(rp, EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
            } catch (Exception e) { continue; }
            if (rep == null || rep.details == null || rep.trackerEvents == null || rep.gameEvents == null) { continue; }

            var players = rep.details.getPlayerList();
            Map<Integer, AbilityMapping> mappings = new HashMap<>();
            for (int i = 0; i < players.length; i++) {
                if (players[i].getRace() != null) {
                    mappings.put(i, new AbilityMapping(i + 1, true, players[i].getRace()));
                }
            }

            List<CmdRecord> cmdRecords = new ArrayList<>();
            for (Event raw : rep.gameEvents.getEvents()) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    for (var m : mappings.values()) { m.onSelection(sel); }
                } else if (raw instanceof CmdEvent cmd) {
                    Integer abilLink = cmd.getAbilLink();
                    if (abilLink == null || abilLink == 42 || abilLink == 45) { continue; }
                    if (cmd.getTargetPoint() != null) { continue; }
                    if (cmd.getTargetUnit() != null) { continue; }
                    AbilityMapping mapping = mappings.get(cmd.getUserId());
                    int selSize = mapping != null ? mapping.selectionSize() : 0;
                    cmdRecords.add(new CmdRecord(cmd.getUserId(), cmd.getLoop(),
                            abilLink, Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0), selSize));
                }
            }

            for (var raw : rep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) { continue; }
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                if (upgrade.getPlayerId() == null) { continue; }
                String name = upgrade.getUpgradeTypeName().toString();
                if (!trackedUpgrades.contains(name)) { continue; }

                int userId = upgrade.getPlayerId() - 1;
                long upgradeLoop = upgrade.getLoop();

                CmdRecord nearest = null;
                CmdRecord nearestSingle = null;
                long bestDist = Long.MAX_VALUE;
                long bestDistSingle = Long.MAX_VALUE;
                for (CmdRecord cr : cmdRecords) {
                    if (cr.userId() != userId) { continue; }
                    long dist = upgradeLoop - cr.loop();
                    if (dist >= 0 && dist < WINDOW) {
                        if (dist < bestDist) { nearest = cr; bestDist = dist; }
                        if (cr.selectionSize() == 1 && dist < bestDistSingle) { nearestSingle = cr; bestDistSingle = dist; }
                    }
                }

                if (nearest != null) {
                    String key = nearest.abilLink() + "/" + nearest.abilCmdIndex();
                    allResults.computeIfAbsent(name, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
                }
                if (nearestSingle != null) {
                    String key = nearestSingle.abilLink() + "/" + nearestSingle.abilCmdIndex();
                    singleSelResults.computeIfAbsent(name, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
                }
            }
        }

        System.out.printf("%n=== Tournament Upgrade → AbilLink Discovery (selection-aware, %d replays) ===%n", replays.size());
        System.out.printf("  Window: %d loops | Columns: [single-selection] / [all]%n%n", WINDOW);

        Set<String> allUpgrades = new java.util.TreeSet<>(allResults.keySet());
        allUpgrades.addAll(singleSelResults.keySet());
        for (String upgrade : allUpgrades) {
            var single = singleSelResults.getOrDefault(upgrade, Map.of());
            var all = allResults.getOrDefault(upgrade, Map.of());
            String singleModal = single.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("-");
            int singleN = single.values().stream().mapToInt(Integer::intValue).sum();
            String allModal = all.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("-");
            int allN = all.values().stream().mapToInt(Integer::intValue).sum();
            System.out.printf("  %-40s single=%-8s (n=%-3d)  all=%-8s (n=%-3d)  detail=%s%n",
                    upgrade, singleModal, singleN, allModal, allN, single);
        }
    }
}
