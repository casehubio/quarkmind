package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.Subgroup;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class BarracksUnitLinkDiscoveryTest {

    private static final Path LADDER_493 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final Map<Integer, String> TRAIN_ABIL_NAMES = Map.ofEntries(
        Map.entry(155, "CC"), Map.entry(159, "Barracks"),
        Map.entry(160, "Factory"), Map.entry(161, "Starport"),
        Map.entry(172, "Gateway"), Map.entry(173, "Stargate"),
        Map.entry(174, "Robotics"), Map.entry(175, "Nexus")
    );

    static boolean ladderReplaysExist() {
        return Files.isDirectory(LADDER_493);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void discoverBuildingUnitLinksViaSingleSelections() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(LADDER_493)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().limit(200).toList();
        }

        // For each abilLink: unitLink → count when selection is single-unit single-subgroup
        Map<Integer, Map<Integer, Integer>> singleUnitResults = new TreeMap<>();
        // For each abilLink: unitLink → count when selection is single-subgroup (any count)
        Map<Integer, Map<Integer, Integer>> singleSubgroupResults = new TreeMap<>();
        // For each abilLink: unitLink → total count across single-subgroup selections (weighted by count)
        Map<Integer, Map<Integer, Integer>> singleSubgroupWeighted = new TreeMap<>();

        for (Integer abil : TRAIN_ABIL_NAMES.keySet()) {
            singleUnitResults.put(abil, new TreeMap<>());
            singleSubgroupResults.put(abil, new TreeMap<>());
            singleSubgroupWeighted.put(abil, new TreeMap<>());
        }

        int totalReplays = 0;

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.gameEvents == null || replay.details == null) continue;
            totalReplays++;

            for (int playerId = 1; playerId <= 2; playerId++) {
                int userId = playerId - 1;
                Subgroup[] lastAddSubgroups = null;

                for (Event raw : replay.gameEvents.getEvents()) {
                    if (raw instanceof SelectionDeltaEvent sel) {
                        if (sel.getUserId() != userId) continue;
                        var delta = sel.getDelta();
                        if (delta == null) {
                            lastAddSubgroups = null;
                            continue;
                        }
                        var sgs = delta.getAddSubgroups();
                        if (sgs != null && sgs.length > 0) {
                            lastAddSubgroups = sgs;
                        } else {
                            lastAddSubgroups = null;
                        }
                    } else if (raw instanceof CmdEvent cmd) {
                        if (cmd.getUserId() != userId) continue;
                        Integer abilLink = cmd.getAbilLink();
                        if (abilLink == null || !TRAIN_ABIL_NAMES.containsKey(abilLink)) continue;
                        if (lastAddSubgroups == null) continue;

                        if (lastAddSubgroups.length == 1) {
                            Subgroup sg = lastAddSubgroups[0];
                            Integer unitLink = sg.getUnitLink();
                            Integer count = sg.getCount();
                            if (unitLink == null || count == null) continue;

                            singleSubgroupResults.get(abilLink).merge(unitLink, 1, Integer::sum);
                            singleSubgroupWeighted.get(abilLink).merge(unitLink, count, Integer::sum);

                            if (count == 1) {
                                singleUnitResults.get(abilLink).merge(unitLink, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("%n=== Building UnitLink Discovery via Single Selections (%d replays) ===%n", totalReplays);

        System.out.println("\n--- Single-unit selections (1 subgroup, count=1) ---");
        System.out.println("    Most likely the actual building unitLink (player selected exactly 1 building)");
        for (var entry : singleUnitResults.entrySet()) {
            int abilLink = entry.getKey();
            String name = TRAIN_ABIL_NAMES.get(abilLink);
            System.out.printf("%n  abilLink=%d (%s):%n", abilLink, name);
            if (entry.getValue().isEmpty()) {
                System.out.println("    (no single-unit selections found)");
            } else {
                entry.getValue().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10)
                    .forEach(e -> System.out.printf("    unitLink=%-4d  count=%d%n",
                        e.getKey(), e.getValue()));
            }
        }

        System.out.println("\n--- Single-subgroup selections (1 subgroup, any count) ---");
        System.out.println("    Player selected N buildings of same type");
        for (var entry : singleSubgroupResults.entrySet()) {
            int abilLink = entry.getKey();
            String name = TRAIN_ABIL_NAMES.get(abilLink);
            System.out.printf("%n  abilLink=%d (%s):%n", abilLink, name);
            if (entry.getValue().isEmpty()) {
                System.out.println("    (no single-subgroup selections found)");
            } else {
                entry.getValue().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10)
                    .forEach(e -> {
                        int totalUnits = singleSubgroupWeighted.get(abilLink).getOrDefault(e.getKey(), 0);
                        double avgCount = e.getValue() > 0 ? (double) totalUnits / e.getValue() : 0;
                        System.out.printf("    unitLink=%-4d  selections=%d  avgBuildingCount=%.1f%n",
                            e.getKey(), e.getValue(), avgCount);
                    });
            }
        }

        System.out.println("\n--- Cross-validation against known unitLinks ---");
        Map<Integer, String> known = Map.of(
            67, "CC", 75, "Factory", 48, "Factory+addon",
            56, "Starport", 49, "Starport+addon",
            84, "Gateway", 81, "Nexus", 106, "Nexus-variant"
        );
        for (var entry : singleUnitResults.entrySet()) {
            int abilLink = entry.getKey();
            String name = TRAIN_ABIL_NAMES.get(abilLink);
            if (entry.getValue().isEmpty()) continue;
            var topEntry = entry.getValue().entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
            if (topEntry != null) {
                String knownName = known.getOrDefault(topEntry.getKey(), "UNKNOWN");
                System.out.printf("  %s (abil=%d): top unitLink=%d (%s), count=%d%n",
                    name, abilLink, topEntry.getKey(), knownName, topEntry.getValue());
            }
        }

        assertThat(totalReplays).isGreaterThan(0);
    }
}
