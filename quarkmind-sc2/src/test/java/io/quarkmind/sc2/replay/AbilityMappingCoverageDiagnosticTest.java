package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic for issue #322 — discovers why AbilityMapping under-counts
 * workers and Zerg Larva-based units by ~60%.
 *
 * Samples stripped ladder replays, runs AbilityMapping, and reports:
 * 1. Which abilLinks produce ReplayCommands vs return empty (dropped)
 * 2. How often the selection-empty guard drops commands by abilLink
 * 3. Frequency of unmapped abilLinks in training-range (100-300)
 */
@Tag("diagnostic")
class AbilityMappingCoverageDiagnosticTest {

    private static final Path LADDER_493 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final Set<Integer> KNOWN_TRAIN_ABIL_LINKS = Set.of(
        155, 159, 160, 161,  // Terran: CC, Barracks, Factory, Starport
        172, 173, 174, 175,  // Protoss: Gateway, Stargate, Robotics, Nexus
        193, 184             // Zerg: Larva, Hatchery
    );

    private static final Set<Integer> KNOWN_OTHER_ABIL_LINKS = Set.of(
        42, 45, 170,         // Move, Attack-move, WarpGate/Probe build
        129, 183,            // SCV build, Drone build
        147, 149, 151,       // Addons
        214, 267             // Warp-in, Archon merge
    );

    static boolean ladderReplaysExist() {
        return Files.isDirectory(LADDER_493);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void diagnoseAbilLinkCoverage() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(LADDER_493)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted()
                .limit(200)
                .toList();
        }

        // Global counters
        Map<Integer, int[]> abilLinkTotal = new TreeMap<>();      // [total, matched, selectionEmpty]
        Map<String, int[]> perUnitMatched = new TreeMap<>();       // unit → [count]
        int totalReplays = 0;
        int parseFailed = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {
                parseFailed++;
                continue;
            }
            if (replay == null || replay.gameEvents == null || replay.details == null) {
                parseFailed++;
                continue;
            }
            totalReplays++;

            // Get player races
            Race[] playerRaces = new Race[2];
            Player[] players = replay.details.getPlayerList();
            if (players != null && players.length >= 2) {
                for (int i = 0; i < Math.min(2, players.length); i++) {
                    playerRaces[i] = players[i] != null ? players[i].getRace() : null;
                }
            }

            // Create mappings for each player — one tracking selection, one not
            AbilityMapping[] mappings = new AbilityMapping[2];
            for (int p = 0; p < 2; p++) {
                mappings[p] = new AbilityMapping(p + 1, true, playerRaces[p]);
            }

            // Track selection state per player for diagnostics
            boolean[] selectionEmpty = new boolean[2];

            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    for (AbilityMapping m : mappings) {
                        m.onSelection(sel);
                    }
                    // Track selection state
                    int uid = sel.getUserId();
                    if (uid >= 0 && uid < 2) {
                        selectionEmpty[uid] = mappings[uid].selectionSnapshotForTest().isEmpty();
                    }
                } else if (raw instanceof CmdEvent cmd) {
                    Integer abilLink = cmd.getAbilLink();
                    if (abilLink == null) continue;
                    int uid = cmd.getUserId();
                    if (uid < 0 || uid > 1) continue;

                    int[] counts = abilLinkTotal.computeIfAbsent(abilLink, k -> new int[3]);
                    counts[0]++; // total

                    List<ReplayCommand> result = mappings[uid].process(cmd);
                    if (!result.isEmpty()) {
                        counts[1]++; // matched
                        // Track per-unit
                        for (ReplayCommand rc : result) {
                            if (rc instanceof ReplayCommand.IntentCommand ic
                                    && ic.intent().intent() instanceof io.quarkmind.sc2.intent.TrainIntent ti) {
                                perUnitMatched.computeIfAbsent(ti.unitType().name(), k -> new int[1])[0]++;
                            }
                        }
                    } else if (selectionEmpty[uid] && KNOWN_TRAIN_ABIL_LINKS.contains(abilLink)) {
                        counts[2]++; // dropped due to empty selection
                    }
                }
            }
        }

        System.out.println("\n=== AbilityMapping Coverage Diagnostic (issue #322) ===");
        System.out.printf("Replays sampled: %d (parse failed: %d)%n%n", totalReplays, parseFailed);

        // Report: training abilLinks
        System.out.println("--- Training abilLinks (production buildings) ---");
        System.out.printf("  %-12s  %8s  %8s  %8s  %8s%n",
            "abilLink", "total", "matched", "selEmpty", "match%");
        for (int abil : KNOWN_TRAIN_ABIL_LINKS) {
            int[] counts = abilLinkTotal.getOrDefault(abil, new int[3]);
            double pct = counts[0] > 0 ? 100.0 * counts[1] / counts[0] : 0;
            System.out.printf("  abil=%-6d    %8d  %8d  %8d  %7.1f%%%n",
                abil, counts[0], counts[1], counts[2], pct);
        }

        // Report: unmapped abilLinks in training range (100-300)
        System.out.println("\n--- Unmapped abilLinks (range 100-300, n>=10) ---");
        System.out.printf("  %-12s  %8s%n", "abilLink", "count");
        Set<Integer> allKnown = new HashSet<>(KNOWN_TRAIN_ABIL_LINKS);
        allKnown.addAll(KNOWN_OTHER_ABIL_LINKS);
        abilLinkTotal.entrySet().stream()
            .filter(e -> e.getKey() >= 100 && e.getKey() <= 300)
            .filter(e -> !allKnown.contains(e.getKey()))
            .filter(e -> e.getValue()[0] >= 10)
            .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
            .forEach(e -> System.out.printf("  abil=%-6d    %8d%n", e.getKey(), e.getValue()[0]));

        // Report: all abilLinks with high frequency that are unmapped
        System.out.println("\n--- All unmapped abilLinks (n>=50) ---");
        System.out.printf("  %-12s  %8s%n", "abilLink", "count");
        abilLinkTotal.entrySet().stream()
            .filter(e -> !allKnown.contains(e.getKey()))
            .filter(e -> e.getValue()[0] >= 50)
            .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
            .forEach(e -> System.out.printf("  abil=%-6d    %8d%n", e.getKey(), e.getValue()[0]));

        // Report: per-unit type match counts
        System.out.println("\n--- Per-unit train matches ---");
        perUnitMatched.forEach((unit, counts) ->
            System.out.printf("  %-20s  %8d%n", unit, counts[0]));

        // Report: top 20 abilLinks by frequency
        System.out.println("\n--- Top 20 abilLinks by frequency ---");
        System.out.printf("  %-12s  %8s  %8s  %7s  %s%n",
            "abilLink", "total", "matched", "match%", "status");
        abilLinkTotal.entrySet().stream()
            .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
            .limit(20)
            .forEach(e -> {
                int abil = e.getKey();
                int[] c = e.getValue();
                double pct = c[0] > 0 ? 100.0 * c[1] / c[0] : 0;
                String status = allKnown.contains(abil)
                    ? (KNOWN_TRAIN_ABIL_LINKS.contains(abil) ? "TRAIN" : "OTHER")
                    : "UNMAPPED";
                System.out.printf("  abil=%-6d    %8d  %8d  %6.1f%%  %s%n",
                    abil, c[0], c[1], pct, status);
            });

        assertThat(totalReplays).as("Must process some replays").isGreaterThan(0);
    }
}
