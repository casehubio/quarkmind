package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import io.quarkmind.sc2.SelectionState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("diagnostic")
class ZergSelectionDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path STRIPPED_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    static boolean oracleExists() {
        return Files.isDirectory(ORACLE_DIR) && Files.isDirectory(STRIPPED_DIR);
    }

    @Test
    @EnabledIf("oracleExists")
    void countLarvaEventsAndSelectionState() throws Exception {
        int totalProbeEvents = 0;
        int probeWithSel = 0;
        int probeWithoutSel = 0;
        Map<Integer, Integer> probeSelSizes = new TreeMap<>();
        Map<Integer, Integer> probeUnitLinks = new TreeMap<>();
        int totalNexusBuild = 0;

        try (var stream = Files.list(ORACLE_DIR)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path replay = STRIPPED_DIR.resolve(oraclePath.getFileName());
                if (!Files.exists(replay)) continue;
                Replay rep = RepParserEngine.parseReplay(replay, EnumSet.of(RepContent.GAME_EVENTS));
                if (rep == null) continue;

                List<Event> events = List.of(rep.gameEvents.getEvents());

                for (int userId = 0; userId <= 1; userId++) {
                    var selTracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(userId);
                    var selection = new io.quarkmind.sc2.SelectionState();
                    for (Event raw : events) {
                        if (raw instanceof SelectionDeltaEvent sel) {
                            selTracker.onSelection(sel);
                            if (sel.getUserId() == userId) {
                                var delta = sel.getDelta();
                                if (delta != null && delta.getAddUnitTags() != null) {
                                    selection.clear();
                                    for (Integer tag : delta.getAddUnitTags()) {
                                        if (tag != null) selection.addTag("t-" + tag);
                                    }
                                }
                            }
                        } else if (raw instanceof CmdEvent cmd && cmd.getUserId() == userId) {
                            Integer abilLink = cmd.getAbilLink();
                            if (abilLink == null) continue;
                            int idx = cmd.getAbilCmdIndex() != null ? cmd.getAbilCmdIndex() : 0;

                            if (abilLink == 175) {
                                totalProbeEvents++;
                                if (selection.isEmpty()) {
                                    probeWithoutSel++;
                                } else {
                                    probeWithSel++;
                                }
                                int matchCount = selTracker.countMatching(java.util.Set.of(81, 106));
                                probeSelSizes.merge(matchCount, 1, Integer::sum);
                                for (Integer link : selTracker.unitLinksSnapshot()) {
                                    probeUnitLinks.merge(link, 1, Integer::sum);
                                }
                            } else if (abilLink == 170 && idx == 0) {
                                if (cmd.getTargetPoint() != null) totalNexusBuild++;
                            }
                        }
                    }
                }
            }
        }

        System.out.println("\n=== Probe Production Diagnostic ===");
        System.out.printf("Total ABIL_NEXUS(175) CmdEvents: %d%n", totalProbeEvents);
        System.out.printf("  With tag selection: %d (%.1f%%)%n", probeWithSel,
                totalProbeEvents > 0 ? 100.0 * probeWithSel / totalProbeEvents : 0);
        System.out.printf("  Without tag selection: %d (%.1f%%)%n", probeWithoutSel,
                totalProbeEvents > 0 ? 100.0 * probeWithoutSel / totalProbeEvents : 0);
        System.out.printf("PROBE_BUILD Nexus (abilLink=170 idx=0): %d%n", totalNexusBuild);
        System.out.println("Nexus unitLink matches (81,106) at Probe train time:");
        for (var e : probeSelSizes.entrySet()) {
            System.out.printf("  matching=%d: %d events%n", e.getKey(), e.getValue());
        }
        System.out.println("All unitLinks in selection at Probe train time:");
        for (var e : probeUnitLinks.entrySet()) {
            System.out.printf("  unitLink=%d: %d occurrences%n", e.getKey(), e.getValue());
        }
    }
}
