package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.Subgroup;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class UnitLinkDiscoveryTest {

    private static final Path LADDER_493 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final Map<Integer, String> TRAIN_ABIL_NAMES = Map.ofEntries(
        Map.entry(155, "CC"), Map.entry(159, "Barracks"),
        Map.entry(160, "Factory"), Map.entry(161, "Starport"),
        Map.entry(172, "Gateway"), Map.entry(173, "Stargate"),
        Map.entry(174, "Robotics"), Map.entry(175, "Nexus"),
        Map.entry(193, "Larva"), Map.entry(184, "Hatchery"),
        Map.entry(186, "Lair")
    );

    static boolean ladderReplaysExist() {
        return Files.isDirectory(LADDER_493);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void discoverUnitLinksForProductionBuildings() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(LADDER_493)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().limit(50).toList();
        }

        // For each abilLink, collect the unitLinks seen in the selection
        Map<Integer, Map<Integer, Integer>> abilLinkToUnitLinks = new TreeMap<>();

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.gameEvents == null || replay.details == null) continue;

            Player[] players = replay.details.getPlayerList();
            if (players.length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                int userId = playerId - 1;
                // Track current selection's unitLink breakdown
                List<Integer> currentUnitLinks = new ArrayList<>();

                for (Event raw : replay.gameEvents.getEvents()) {
                    if (raw instanceof SelectionDeltaEvent sel) {
                        if (sel.getUserId() != userId) continue;
                        var delta = sel.getDelta();
                        if (delta == null) { currentUnitLinks.clear(); continue; }

                        var removeMask = delta.getRemoveMask();
                        String variant = removeMask != null ? removeMask.value1 : null;
                        if ("ZeroIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
                            List<Integer> kept = new ArrayList<>();
                            for (int idx : indices) {
                                if (idx >= 0 && idx < currentUnitLinks.size()) {
                                    kept.add(currentUnitLinks.get(idx));
                                }
                            }
                            currentUnitLinks = kept;
                        } else if ("OneIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
                            for (int i = indices.length - 1; i >= 0; i--) {
                                int idx = indices[i];
                                if (idx >= 0 && idx < currentUnitLinks.size()) {
                                    currentUnitLinks.remove(idx);
                                }
                            }
                        } else if (variant != null && !"None".equals(variant)) {
                            currentUnitLinks.clear();
                        }

                        var addSubgroups = delta.getAddSubgroups();
                        if (addSubgroups != null) {
                            for (Subgroup sg : addSubgroups) {
                                Integer unitLink = sg.getUnitLink();
                                Integer count = sg.getCount();
                                if (unitLink != null && count != null) {
                                    for (int i = 0; i < count; i++) {
                                        currentUnitLinks.add(unitLink);
                                    }
                                }
                            }
                        }
                    } else if (raw instanceof CmdEvent cmd) {
                        if (cmd.getUserId() != userId) continue;
                        Integer abilLink = cmd.getAbilLink();
                        if (abilLink == null || !TRAIN_ABIL_NAMES.containsKey(abilLink)) continue;

                        var counts = abilLinkToUnitLinks.computeIfAbsent(abilLink, k -> new TreeMap<>());
                        for (Integer unitLink : currentUnitLinks) {
                            counts.merge(unitLink, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        System.out.println("\n=== UnitLink Discovery for Production Buildings ===");
        for (var entry : abilLinkToUnitLinks.entrySet()) {
            int abilLink = entry.getKey();
            String name = TRAIN_ABIL_NAMES.get(abilLink);
            System.out.printf("\nabilLink=%d (%s):%n", abilLink, name);
            entry.getValue().entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> System.out.printf("  unitLink=%-4d  count=%d%n",
                    e.getKey(), e.getValue()));
        }

        assertThat(abilLinkToUnitLinks).isNotEmpty();
    }
}
