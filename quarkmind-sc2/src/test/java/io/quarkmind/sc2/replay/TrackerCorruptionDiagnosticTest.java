package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.Subgroup;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class TrackerCorruptionDiagnosticTest {

    private static final Path STRIPPED_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final String WORST_REPLAY =
        "15a050a38e806478b16298fed5e818a859b1ec194e32f36405c595a6ce237ab5.SC2Replay";

    static boolean replayExists() {
        return Files.isRegularFile(STRIPPED_DIR.resolve(WORST_REPLAY));
    }

    @Test
    @EnabledIf("replayExists")
    void traceSelectionGrowthForP2() {
        Path replayPath = STRIPPED_DIR.resolve(WORST_REPLAY);
        Replay replay = RepParserEngine.parseReplay(replayPath,
            EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
        assertThat(replay).isNotNull();
        assertThat(replay.gameEvents).isNotNull();

        int targetUserId = 1; // P2
        var unitLinks = new ArrayList<Integer>();
        int prevSize = 0;

        System.out.printf("%n=== Selection Delta Trace for P2 (%s) ===%n%n", WORST_REPLAY);
        System.out.printf("%-8s  %-14s  %-6s  %-6s  %-6s  %-6s  %s%n",
            "Loop", "Variant", "Before", "Rmvd", "Added", "After", "Details");
        System.out.println("-".repeat(120));

        for (Event raw : replay.gameEvents.getEvents()) {
            if (!(raw instanceof SelectionDeltaEvent sel)) continue;
            if (sel.getUserId() != targetUserId) continue;

            var delta = sel.getDelta();
            if (delta == null) {
                int before = unitLinks.size();
                unitLinks.clear();
                System.out.printf("%-8d  %-14s  %-6d  %-6d  %-6d  %-6d  delta=null%n",
                    sel.getLoop(), "null-delta", before, before, 0, 0);
                prevSize = 0;
                continue;
            }

            var removeMask = delta.getRemoveMask();
            String variant = removeMask != null ? removeMask.value1 : null;
            Object value2 = removeMask != null ? removeMask.value2 : null;

            int beforeSize = unitLinks.size();
            String variantLabel = variant != null ? variant : "null";
            String removeDetail = "";

            if ("ZeroIndices".equals(variant) && value2 instanceof Integer[] indices) {
                var kept = new ArrayList<Integer>();
                for (int idx : indices) {
                    if (idx >= 0 && idx < unitLinks.size()) kept.add(unitLinks.get(idx));
                }
                unitLinks.clear();
                unitLinks.addAll(kept);
                removeDetail = "keep=" + indices.length;
            } else if ("OneIndices".equals(variant) && value2 instanceof Integer[] indices) {
                for (int i = indices.length - 1; i >= 0; i--) {
                    int idx = indices[i];
                    if (idx >= 0 && idx < unitLinks.size()) unitLinks.remove(idx);
                }
                removeDetail = "remove=" + indices.length;
            } else if ("Mask".equals(variant) && value2 instanceof hu.belicza.andras.util.type.BitArray bitArray) {
                int removed = 0;
                for (int i = unitLinks.size() - 1; i >= 0; i--) {
                    if (i < bitArray.getCount() && bitArray.getBit(i)) {
                        unitLinks.remove(i);
                        removed++;
                    }
                }
                removeDetail = "maskBits=" + bitArray.getCount() + " removed=" + removed;
            } else if (variant != null && !"None".equals(variant)) {
                unitLinks.clear();
                removeDetail = "unknown-clear";
            } else {
                removeDetail = "no-remove";
                if (value2 != null) {
                    removeDetail += " value2Type=" + value2.getClass().getSimpleName();
                }
            }

            int afterRemoveSize = unitLinks.size();
            int removed = beforeSize - afterRemoveSize;

            var subgroups = delta.getAddSubgroups();
            int added = 0;
            var addDetail = new TreeMap<Integer, Integer>();
            if (subgroups != null) {
                for (var sg : subgroups) {
                    Integer link = sg.getUnitLink();
                    Integer count = sg.getCount();
                    if (link != null && count != null) {
                        for (int i = 0; i < count; i++) unitLinks.add(link);
                        added += count;
                        addDetail.merge(link, count, Integer::sum);
                    }
                }
            }

            int afterSize = unitLinks.size();
            boolean suspicious = afterSize > prevSize + 20 || afterSize > 100;
            String marker = suspicious ? " <<<" : "";

            if (afterSize > 10 || beforeSize > 10 || suspicious || Math.abs(afterSize - prevSize) > 10) {
                System.out.printf("%-8d  %-14s  %-6d  %-6d  %-6d  %-6d  %s add=%s%s%n",
                    sel.getLoop(), variantLabel, beforeSize, removed, added, afterSize,
                    removeDetail, addDetail.isEmpty() ? "{}" : addDetail, marker);
            }

            prevSize = afterSize;
        }

        System.out.printf("%nFinal tracker size: %d%n", unitLinks.size());

        // Also show the unitLink breakdown at the end
        var finalBreakdown = new TreeMap<Integer, Integer>();
        for (int link : unitLinks) {
            finalBreakdown.merge(link, 1, Integer::sum);
        }
        System.out.printf("Final breakdown: %s%n", finalBreakdown);
    }
}
