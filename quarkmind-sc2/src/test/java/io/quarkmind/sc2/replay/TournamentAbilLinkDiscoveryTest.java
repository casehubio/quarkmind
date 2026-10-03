package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
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

        Map<String, Map<String, Integer>> upgradeToAbilLink = new TreeMap<>();

        for (Path rp : replays.stream().sorted().toList()) {
            Replay rep;
            try {
                rep = RepParserEngine.parseReplay(rp, EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
            } catch (Exception e) { continue; }
            if (rep == null || rep.details == null || rep.trackerEvents == null || rep.gameEvents == null) { continue; }

            var players = rep.details.getPlayerList();
            Map<Integer, Race> playerRaces = new HashMap<>();
            for (int i = 0; i < players.length; i++) {
                if (players[i].getRace() != null) { playerRaces.put(i, players[i].getRace()); }
            }

            List<CmdEvent> cmdEvents = new ArrayList<>();
            for (Event raw : rep.gameEvents.getEvents()) {
                if (raw instanceof CmdEvent cmd) { cmdEvents.add(cmd); }
            }

            for (var raw : rep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) { continue; }
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                if (upgrade.getPlayerId() == null) { continue; }
                String name = upgrade.getUpgradeTypeName().toString();
                if (!trackedUpgrades.contains(name)) { continue; }

                int userId = upgrade.getPlayerId() - 1;
                long upgradeLoop = upgrade.getLoop();

                CmdEvent nearest = null;
                long bestDist = Long.MAX_VALUE;
                for (CmdEvent cmd : cmdEvents) {
                    if (cmd.getUserId() != userId) { continue; }
                    Integer abilLink = cmd.getAbilLink();
                    if (abilLink == null || abilLink == 42 || abilLink == 45) { continue; }
                    if (cmd.getTargetPoint() != null) { continue; }
                    if (cmd.getTargetUnit() != null) { continue; }
                    long dist = upgradeLoop - cmd.getLoop();
                    if (dist >= 0 && dist < WINDOW && dist < bestDist) {
                        nearest = cmd;
                        bestDist = dist;
                    }
                }

                if (nearest != null) {
                    Integer al = nearest.getAbilLink();
                    int idx = Objects.requireNonNullElse(nearest.getAbilCmdIndex(), 0);
                    String key = al + "/" + idx;
                    upgradeToAbilLink.computeIfAbsent(name, k -> new TreeMap<>())
                            .merge(key, 1, Integer::sum);
                }
            }
        }

        System.out.printf("%n=== Tournament Upgrade → AbilLink Discovery (HSC, %d replays) ===%n", replays.size());
        System.out.printf("  Window: %d loops%n%n", WINDOW);

        for (var entry : upgradeToAbilLink.entrySet()) {
            String upgrade = entry.getKey();
            var candidates = entry.getValue();
            String modal = candidates.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("?");
            int totalHits = candidates.values().stream().mapToInt(Integer::intValue).sum();
            System.out.printf("  %-40s modal=%-8s n=%-4d  all=%s%n", upgrade, modal, totalHits, candidates);
        }
    }
}
