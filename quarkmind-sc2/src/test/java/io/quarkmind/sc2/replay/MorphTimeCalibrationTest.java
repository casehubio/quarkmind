package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration test: empirically determines morph times by correlating
 * morph CmdEvents (via AbilityMapping) with UnitTypeChange tracker events.
 *
 * UnitTypeChange events lack controlPlayerId, so correlation uses a
 * range-bounded modal approach: for each (morphCmd, typeChange) pair
 * within the expected morph-time window, count diff occurrences.
 * The modal value is T_real.
 *
 * Sources:
 *   A — AI Arena bot replays (29 games, PvZ/PvT/PvP)
 *   B — Blizzard ladder replays (first 100 with morph events)
 */
@Tag("diagnostic")
class MorphTimeCalibrationTest {

    private static final Path AIARENA_DIR = Path.of("replays/aiarena_protoss");
    private static final Path LADDER_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.10.1/replays");

    private static final Map<String, String> MORPH_TARGET_TO_SOURCE = Map.of(
        "Baneling", "Zergling",
        "Ravager", "Roach",
        "Lurker", "Hydralisk",
        "BroodLord", "Corruptor",
        "Overseer", "Overlord"
    );

    private static final Map<String, int[]> EXPECTED_RANGES = Map.of(
        "Baneling",  new int[]{300, 600},
        "Ravager",   new int[]{150, 400},
        "Lurker",    new int[]{250, 550},
        "BroodLord", new int[]{400, 700},
        "Overseer",  new int[]{150, 400}
    );

    static boolean aiarenaExists() {
        return Files.isDirectory(AIARENA_DIR);
    }

    static boolean ladderExists() {
        return Files.isDirectory(LADDER_DIR);
    }

    @Test
    @EnabledIf("aiarenaExists")
    void calibrateMorphTimesFromAiArena() throws Exception {
        Map<String, List<Long>> allMorphCmds = new TreeMap<>();
        Map<String, List<Long>> allTypeChanges = new TreeMap<>();
        int replayCount = 0;

        try (var stream = Files.list(AIARENA_DIR)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                try {
                    accumulateFromReplay(rp, allMorphCmds, allTypeChanges);
                    replayCount++;
                } catch (Exception e) {
                    // skip unparseable replays
                }
            }
        }

        System.out.printf("%n=== AI Arena Morph Time Calibration (%d replays) ===%n", replayCount);
        printRawCounts(allMorphCmds, allTypeChanges);
        Map<String, Integer> calibrated = calibrate(allMorphCmds, allTypeChanges, "AI Arena");

        System.out.println("\n=== Calibrated Morph Times ===");
        for (var e : calibrated.entrySet()) {
            System.out.printf("  %-12s  T_real = %d loops  (%.1f seconds)%n",
                e.getKey(), e.getValue(), e.getValue() / 22.4);
        }

        assertThat(calibrated).as("Must discover at least one morph time").isNotEmpty();
    }

    @Test
    @EnabledIf("ladderExists")
    void calibrateMorphTimesFromLadder() throws Exception {
        Map<String, List<Long>> allMorphCmds   = new TreeMap<>();
        Map<String, List<Long>> allTypeChanges = new TreeMap<>();
        int                     replayCount    = 0;
        int                     morphReplays   = 0;

        try (var stream = Files.list(LADDER_DIR)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                if (replayCount >= 500) {break;}
                replayCount++;
                int beforeSize = allMorphCmds.values().stream().mapToInt(List::size).sum();
                try {
                    accumulateFromReplay(rp, allMorphCmds, allTypeChanges);
                } catch (Exception e) {
                    continue;
                }
                int afterSize = allMorphCmds.values().stream().mapToInt(List::size).sum();
                if (afterSize > beforeSize) {morphReplays++;}
                if (morphReplays >= 100) {break;}
            }
        }

        System.out.printf("%n=== Ladder Morph Time Calibration (%d scanned, %d with morphs) ===%n",
                          replayCount, morphReplays);
        printRawCounts(allMorphCmds, allTypeChanges);
        Map<String, Integer> calibrated = calibrate(allMorphCmds, allTypeChanges, "Ladder");

        System.out.println("\n=== Calibrated Morph Times (Ladder) ===");
        for (var e : calibrated.entrySet()) {
            System.out.printf("  %-12s  T_real = %d loops  (%.1f seconds)%n",
                              e.getKey(), e.getValue(), e.getValue() / 22.4);
        }

        // Ladder replays are stripped (no tracker events) — this test documents the gap
        // and will start producing results when full ladder replays become available
        if (calibrated.isEmpty()) {
            System.out.println("  (No morph times calibrated — ladder replays lack tracker events)");
        }
    }

    private void accumulateFromReplay(Path replayPath,
                                      Map<String, List<Long>> morphCmds,
                                      Map<String, List<Long>> typeChanges) {
        Replay replay = RepParserEngine.parseReplay(replayPath,
            EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
        if (replay == null || replay.gameEvents == null || replay.trackerEvents == null
            || replay.details == null) return;

        Player[] players = replay.details.getPlayerList();
        if (players.length < 2) return;

        for (int playerId = 1; playerId <= Math.min(players.length, 2); playerId++) {
            Race race = players[playerId - 1].getRace();
            if (race != Race.ZERG && race != Race.TERRAN && race != Race.PROTOSS) continue;

            int userId = playerId - 1;
            var mapping = new AbilityMapping(playerId, true, race);

            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    mapping.onSelection(sel);
                } else if (raw instanceof CmdEvent cmd) {
                    if (cmd.getUserId() != userId) continue;
                    List<ReplayCommand> commands = mapping.process(cmd);
                    for (ReplayCommand rc : commands) {
                        if (rc instanceof ReplayCommand.MorphCommand mc) {
                            String target = mc.targetName();
                            if (MORPH_TARGET_TO_SOURCE.containsKey(target)) {
                                morphCmds.computeIfAbsent(target, k -> new ArrayList<>())
                                    .add(mc.loop());
                            }
                        }
                    }
                }
            }
        }

        for (Event raw : replay.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UNIT_TYPE_CHANGE) continue;
            Object nameObj = raw.get("unitTypeName");
            String unitName = nameObj != null ? nameObj.toString() : null;
            if (unitName == null || !MORPH_TARGET_TO_SOURCE.containsKey(unitName)) continue;
            typeChanges.computeIfAbsent(unitName, k -> new ArrayList<>())
                .add((long) raw.getLoop());
        }
    }

    private void printRawCounts(Map<String, List<Long>> morphCmds,
                                Map<String, List<Long>> typeChanges) {
        System.out.println("  Raw counts:");
        for (String target : MORPH_TARGET_TO_SOURCE.keySet().stream().sorted().toList()) {
            int cmdCount = morphCmds.getOrDefault(target, List.of()).size();
            int tcCount = typeChanges.getOrDefault(target, List.of()).size();
            System.out.printf("    %-12s  morphCmds=%d  typeChanges=%d%n", target, cmdCount, tcCount);
        }
    }

    private Map<String, Integer> calibrate(Map<String, List<Long>> morphCmds,
                                           Map<String, List<Long>> typeChanges,
                                           String label) {
        Map<String, Integer> result = new TreeMap<>();

        for (var rangeEntry : EXPECTED_RANGES.entrySet()) {
            String target = rangeEntry.getKey();
            int tMin = rangeEntry.getValue()[0];
            int tMax = rangeEntry.getValue()[1];

            List<Long> cmds = morphCmds.getOrDefault(target, List.of());
            List<Long> changes = typeChanges.getOrDefault(target, List.of());
            if (cmds.isEmpty() || changes.isEmpty()) continue;

            Map<Integer, Integer> freq = new TreeMap<>();
            for (long change : changes) {
                for (long cmd : cmds) {
                    int diff = (int) (change - cmd);
                    if (diff >= tMin && diff <= tMax) {
                        freq.merge(diff, 1, Integer::sum);
                    }
                }
            }
            if (freq.isEmpty()) {
                System.out.printf("  [%s] %-12s  NO MATCHES in range [%d, %d]%n",
                    label, target, tMin, tMax);
                if (!cmds.isEmpty() && !changes.isEmpty()) {
                    long firstCmd = cmds.stream().min(Long::compareTo).orElse(0L);
                    long firstChange = changes.stream().min(Long::compareTo).orElse(0L);
                    System.out.printf("    first cmd=%d, first typeChange=%d, diff=%d%n",
                        firstCmd, firstChange, firstChange - firstCmd);
                }
                continue;
            }

            var best = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();
            int modal = best.getKey();
            int count = best.getValue();

            System.out.printf("  [%s] T_real(%-12s) = %d  (count=%d, total_pairs=%d)%n",
                label, target, modal, count, freq.values().stream().mapToInt(Integer::intValue).sum());

            if (count >= 2) {
                result.put(target, modal);
            }
        }
        return result;
    }
}
