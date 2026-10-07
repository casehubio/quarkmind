package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UpgradeType;
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

/**
 * Direct per-replay upgrade-to-abilLink matching. For each upgrade in the tracker events,
 * looks at ALL CmdEvents from the same player at exactly (upgradeLoop - researchTime ± 150 loops)
 * to find the research command. This tight-window approach eliminates noise.
 *
 * The key insight: if we know WHEN a specific upgrade completed AND the research duration,
 * we know exactly when the research was started. The CmdEvent at that loop is the research command.
 */
@Tag("diagnostic")
class AbilLinkEnumerationTest {

    private static final Path REPLAY_PACKS = Path.of("../quarkmind-classifier/data/replay_packs");
    private static final Path ASUS_ROG_2020 = REPLAY_PACKS.resolve("2020_ASUS_ROG_Online");
    private static final Path IEM_2018 = REPLAY_PACKS.resolve("2018_IEM_PyeongChang");

    static boolean asusRogExists() { return Files.isDirectory(ASUS_ROG_2020); }
    static boolean iemExists() { return Files.isDirectory(IEM_2018); }

    @Test
    @EnabledIf("asusRogExists")
    void discoverAsusRog2020UpgradeAbilLinksExact() throws Exception {
        System.out.println("\n=== ASUS ROG 2020 — Exact Upgrade AbilLink Discovery ===\n");
        discoverExactUpgradeAbilLinks(ASUS_ROG_2020);
    }

    @Test
    @EnabledIf("iemExists")
    void discoverIem2018UpgradeAbilLinksExact() throws Exception {
        System.out.println("\n=== IEM 2018 — Exact Upgrade AbilLink Discovery ===\n");
        discoverExactUpgradeAbilLinks(IEM_2018);
    }

    private void discoverExactUpgradeAbilLinks(Path datasetDir) throws Exception {
        Map<String, Integer> researchTimes = new HashMap<>();
        for (var ut : UpgradeType.values()) {
            researchTimes.put(ut.pythonName(), SC2Data.upgradeTimeInLoops(ut));
        }

        // upgrade → (abilLink/idx → count)
        Map<String, Map<String, Integer>> upgradeToAbilLink = new TreeMap<>();
        Map<String, Integer> upgradeCounts = new TreeMap<>();
        int replayCount = 0;

        try (var stream = Files.list(datasetDir)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay rep;
                try {
                    rep = RepParserEngine.parseReplay(rp,
                        EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
                } catch (Exception e) { continue; }
                if (rep == null || rep.details == null || rep.trackerEvents == null || rep.gameEvents == null) continue;
                replayCount++;

                var players = rep.details.getPlayerList();
                Map<Integer, Race> playerRaces = new HashMap<>();
                for (int i = 0; i < players.length; i++) {
                    if (players[i].getRace() != null) playerRaces.put(i, players[i].getRace());
                }

                // Collect ALL CmdEvents (including with target points)
                List<CmdRecord> allCmds = new ArrayList<>();
                for (var raw : rep.gameEvents.getEvents()) {
                    if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                        allCmds.add(new CmdRecord(
                            cmd.getUserId() + 1, cmd.getLoop(), cmd.getAbilLink(),
                            Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0),
                            cmd.getTargetPoint() != null,
                            playerRaces.getOrDefault(cmd.getUserId(), Race.UNKNOWN)));
                    }
                }

                // For each tracker upgrade, find CmdEvents at the exact research start time
                for (Event raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                    IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                    if (upgrade.getPlayerId() == null) continue;
                    String upgradeName = upgrade.getUpgradeTypeName().toString();
                    Integer researchTime = researchTimes.get(upgradeName);
                    if (researchTime == null) continue;

                    upgradeCounts.merge(upgradeName, 1, Integer::sum);
                    long upgradeLoop = upgrade.getLoop();
                    long expectedStart = upgradeLoop - researchTime;

                    // Find ALL CmdEvents within ±150 loops of expected start
                    for (CmdRecord cmd : allCmds) {
                        if (cmd.playerId != upgrade.getPlayerId()) continue;
                        long dist = Math.abs(cmd.loop - expectedStart);
                        if (dist > 150) continue;
                        // Skip obvious movement commands
                        if (cmd.abilLink == 42 || cmd.abilLink == 45 || cmd.abilLink == 46) continue;

                        String key = String.format("%d/%d%s [%s]",
                            cmd.abilLink, cmd.abilCmdIndex,
                            cmd.hasTargetPoint ? " TP" : "",
                            cmd.race.toString().substring(0, 1));
                        upgradeToAbilLink.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                            .merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.printf("  Replays: %d%n%n", replayCount);
        System.out.printf("  %-40s %5s  %-25s %5s%n", "Upgrade", "total", "Best abilLink/idx", "hits");
        System.out.println("  " + "-".repeat(85));

        for (var entry : upgradeToAbilLink.entrySet()) {
            String upgradeName = entry.getKey();
            int total = upgradeCounts.getOrDefault(upgradeName, 0);
            var sorted = entry.getValue().entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .toList();
            if (sorted.isEmpty()) continue;

            var best = sorted.get(0);
            double hitRate = total > 0 ? 100.0 * best.getValue() / total : 0;
            System.out.printf("  %-40s %5d  %-25s %5d (%.0f%%)%n",
                upgradeName, total, best.getKey(), best.getValue(), hitRate);
            for (int i = 1; i < sorted.size(); i++) {
                var alt = sorted.get(i);
                System.out.printf("  %-40s %5s  %-25s %5d%n", "", "", alt.getKey(), alt.getValue());
            }
        }

        // Summary: upgrades with no match at expected start time
        System.out.println("\n  Upgrades with NO match at expected start time:");
        for (var entry : upgradeCounts.entrySet()) {
            if (!upgradeToAbilLink.containsKey(entry.getKey())) {
                System.out.printf("    %-40s %d events%n", entry.getKey(), entry.getValue());
            }
        }
    }

    record CmdRecord(int playerId, long loop, int abilLink, int abilCmdIndex,
                     boolean hasTargetPoint, Race race) {}
}
