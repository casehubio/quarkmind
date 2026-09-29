package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class MarineMultiplicationDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path STRIPPED_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final int ABIL_BARRACKS = 159;
    private static final Set<Integer> BARRACKS_UNIT_LINKS = Set.of(70);

    static boolean oracleAndOriginalsExist() {
        if (!Files.isDirectory(ORACLE_DIR) || !Files.isDirectory(STRIPPED_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    record ReplayResult(String filename,
                        int p1Oracle, int p1Java, int p1MaxMult, double p1AvgMult, int p1CmdCount,
                        int p2Oracle, int p2Java, int p2MaxMult, double p2AvgMult, int p2CmdCount,
                        List<CmdDetail> worstPlayerCmds) {}

    record CmdDetail(int loop, int multFactor, Map<Integer, Integer> selectionBreakdown) {}

    @Test
    @EnabledIf("oracleAndOriginalsExist")
    void diagnoseMarineMultiplicationPerReplay() throws Exception {
        List<Path> oracleReplays;
        try (var stream = Files.list(ORACLE_DIR)) {
            oracleReplays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        List<ReplayResult> results = new ArrayList<>();

        for (Path oraclePath : oracleReplays) {
            Path strippedPath = STRIPPED_DIR.resolve(oraclePath.getFileName());
            if (!Files.exists(strippedPath)) continue;

            // Oracle Marine counts
            int[] oracleMarines = extractOracleMarines(oraclePath);
            if (oracleMarines == null) continue;

            // Java Marine counts with multiplication tracking
            int[][] javaCounts = extractJavaMarinesWithMultTracking(strippedPath);
            // javaCounts[player][0]=total, [1]=maxMult, [2]=sumMult, [3]=cmdCount

            results.add(new ReplayResult(
                oraclePath.getFileName().toString(),
                oracleMarines[0], javaCounts[0][0], javaCounts[0][1],
                javaCounts[0][3] > 0 ? (double) javaCounts[0][2] / javaCounts[0][3] : 0,
                javaCounts[0][3],
                oracleMarines[1], javaCounts[1][0], javaCounts[1][1],
                javaCounts[1][3] > 0 ? (double) javaCounts[1][2] / javaCounts[1][3] : 0,
                javaCounts[1][3],
                null
            ));
        }

        // Sort by P2 over-counting ratio
        results.sort((a, b) -> {
            double ratioA = a.p2Oracle > 0 ? (double) a.p2Java / a.p2Oracle : 0;
            double ratioB = b.p2Oracle > 0 ? (double) b.p2Java / b.p2Oracle : 0;
            return Double.compare(ratioB, ratioA);
        });

        // Print per-replay summary
        System.out.println("\n=== Marine Multiplication Per-Replay Diagnostic ===\n");
        System.out.printf("%-45s  %6s %6s %5s %5s %5s   %6s %6s %5s %5s %5s%n",
            "Replay", "P1-orc", "P1-jav", "maxM", "avgM", "cmds",
            "P2-orc", "P2-jav", "maxM", "avgM", "cmds");
        System.out.println("-".repeat(130));

        int p2OverCount = 0;
        int p2UnderCount = 0;
        int p2Exact = 0;
        for (ReplayResult r : results) {
            double p2Ratio = r.p2Oracle > 0 ? (double) r.p2Java / r.p2Oracle : 0;
            String marker = "";
            if (r.p2Oracle > 0 && p2Ratio > 1.2) { marker = " <<<"; p2OverCount++; }
            else if (r.p2Oracle > 0 && p2Ratio < 0.8) { p2UnderCount++; }
            else if (r.p2Oracle > 0) { p2Exact++; }
            System.out.printf("%-45s  %6d %6d %5d %5.1f %5d   %6d %6d %5d %5.1f %5d%s%n",
                r.filename.substring(0, Math.min(45, r.filename.length())),
                r.p1Oracle, r.p1Java, r.p1MaxMult, r.p1AvgMult, r.p1CmdCount,
                r.p2Oracle, r.p2Java, r.p2MaxMult, r.p2AvgMult, r.p2CmdCount, marker);
        }

        System.out.printf("%nP2 over-count (>120%%): %d replays, under-count (<80%%): %d, within range: %d%n",
            p2OverCount, p2UnderCount, p2Exact);

        // Top 10 worst P2 over-counting
        System.out.println("\n=== Top 10 Worst P2 Over-Counting Replays ===\n");
        List<ReplayResult> worst10 = results.stream()
            .filter(r -> r.p2Oracle > 0)
            .limit(10)
            .toList();

        for (ReplayResult r : worst10) {
            double ratio = (double) r.p2Java / r.p2Oracle;
            System.out.printf("  %s — P2: oracle=%d java=%d ratio=%.1f%% maxMult=%d avgMult=%.1f cmds=%d%n",
                r.filename.substring(0, Math.min(45, r.filename.length())),
                r.p2Oracle, r.p2Java, ratio * 100, r.p2MaxMult, r.p2AvgMult, r.p2CmdCount);
        }

        // Deep-dive on worst replay
        if (!worst10.isEmpty()) {
            ReplayResult worst = worst10.get(0);
            Path worstStripped = STRIPPED_DIR.resolve(worst.filename);
            System.out.printf("%n=== Deep Dive: %s (P2) ===%n%n", worst.filename);
            deepDiveMarineCmds(worstStripped, 1); // userId=1 for P2
        }

        // Also show P1 stats summary
        int p1Over = 0, p1Under = 0, p1Ok = 0;
        for (ReplayResult r : results) {
            if (r.p1Oracle == 0) continue;
            double ratio = (double) r.p1Java / r.p1Oracle;
            if (ratio > 1.2) p1Over++;
            else if (ratio < 0.8) p1Under++;
            else p1Ok++;
        }
        System.out.printf("%nP1 summary: over(>120%%): %d, under(<80%%): %d, ok: %d%n", p1Over, p1Under, p1Ok);

        assertThat(results).isNotEmpty();
    }

    private int[] extractOracleMarines(Path oraclePath) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(oraclePath,
                EnumSet.of(RepContent.TRACKER_EVENTS));
        } catch (Exception e) { return null; }
        if (replay == null || replay.trackerEvents == null) return null;

        int[] counts = new int[2]; // P1, P2
        Event[] events = replay.trackerEvents.getEvents();
        boolean debugged = oraclePath.getFileName().toString().startsWith("008c");
        int totalEvents = events.length;
        int unitBornCount = 0;
        int marineCount = 0;
        Set<String> unitNames = debugged ? new TreeSet<>() : null;
        for (Event raw : events) {
            if (raw.getId() == ITrackerEvents.ID_UNIT_BORN) {
                unitBornCount++;
                IBaseUnitEvent born = (IBaseUnitEvent) raw;
                if (debugged) {
                    String typeName = String.valueOf(born.getUnitTypeName());
                    unitNames.add(typeName);
                }
                if (born.getControlPlayerId() == null || born.getControlPlayerId() == 0) continue;
                if (born.getLoop() == 0) continue;
                if ("Marine".equals(String.valueOf(born.getUnitTypeName()))) {
                    marineCount++;
                    int pid = born.getControlPlayerId();
                    if (pid == 1) counts[0]++;
                    else if (pid == 2) counts[1]++;
                }
            }
        }
        if (debugged) {
            System.out.printf("  DEBUG %s: totalEvents=%d unitBorn=%d marines=%d counts=[%d,%d]%n",
                oraclePath.getFileName(), totalEvents, unitBornCount, marineCount, counts[0], counts[1]);
            System.out.printf("  DEBUG unit types: %s%n", unitNames);
        }
        return counts;
    }

    private int[][] extractJavaMarinesWithMultTracking(Path strippedPath) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(strippedPath,
                EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
        } catch (Exception e) { return new int[][]{{0,0,0,0},{0,0,0,0}}; }
        if (replay == null || replay.gameEvents == null || replay.details == null)
            return new int[][]{{0,0,0,0},{0,0,0,0}};

        Player[] players = replay.details.getPlayerList();
        if (players.length < 2) return new int[][]{{0,0,0,0},{0,0,0,0}};

        // [player][total, maxMult, sumMult, cmdCount]
        int[][] result = new int[2][4];

        for (int playerId = 1; playerId <= 2; playerId++) {
            int pi = playerId - 1;
            var playerRace = players[pi].getRace();
            var mapping = new AbilityMapping(playerId, true, playerRace);
            var selTracker = new StrippedReplayFeatureExtractor.SelectionUnitLinkTracker(pi);

            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    mapping.onSelection(sel);
                    selTracker.onSelection(sel);
                } else if (raw instanceof CmdEvent cmd) {
                    for (ReplayCommand rc : mapping.process(cmd)) {
                        if (rc instanceof ReplayCommand.IntentCommand ic
                            && ic.intent().intent() instanceof TrainIntent train
                            && train.unitType() == UnitType.MARINE) {

                            Integer abilLink = cmd.getAbilLink();
                            int mult = 1;
                            if (abilLink != null) {
                                int matching = selTracker.countMatching(BARRACKS_UNIT_LINKS);
                                mult = Math.max(1, matching);
                            }

                            int trainCount = io.quarkmind.domain.SC2Data.trainCount(UnitType.MARINE);
                            result[pi][0] += mult * trainCount;
                            result[pi][1] = Math.max(result[pi][1], mult);
                            result[pi][2] += mult;
                            result[pi][3]++;
                        }
                    }
                }
            }
        }
        return result;
    }

    private void deepDiveMarineCmds(Path strippedPath, int targetUserId) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(strippedPath,
                EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
        } catch (Exception e) { System.out.println("  Failed to parse: " + e.getMessage()); return; }
        if (replay == null || replay.gameEvents == null || replay.details == null) return;

        Player[] players = replay.details.getPlayerList();
        if (players.length < 2) return;

        int playerId = targetUserId + 1;
        var playerRace = players[targetUserId].getRace();
        var mapping = new AbilityMapping(playerId, true, playerRace);

        // Track selection inline (mirrors SelectionUnitLinkTracker logic)
        List<Integer> currentUnitLinks = new ArrayList<>();

        System.out.printf("  %-8s  %-6s  %-6s  %s%n", "Loop", "Mult", "SelSz", "Selection unitLinks breakdown");
        System.out.println("  " + "-".repeat(90));

        for (Event raw : replay.gameEvents.getEvents()) {
            if (raw instanceof SelectionDeltaEvent sel) {
                mapping.onSelection(sel);
                if (sel.getUserId() != targetUserId) continue;
                var delta = sel.getDelta();
                if (delta == null) { currentUnitLinks.clear(); continue; }

                var removeMask = delta.getRemoveMask();
                String variant = removeMask != null ? removeMask.value1 : null;
                if ("ZeroIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
                    var kept = new ArrayList<Integer>();
                    for (int idx : indices) {
                        if (idx >= 0 && idx < currentUnitLinks.size()) kept.add(currentUnitLinks.get(idx));
                    }
                    currentUnitLinks = kept;
                } else if ("OneIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
                    for (int i = indices.length - 1; i >= 0; i--) {
                        int idx = indices[i];
                        if (idx >= 0 && idx < currentUnitLinks.size()) currentUnitLinks.remove(idx);
                    }
                } else if ("Mask".equals(variant) && removeMask.value2 instanceof hu.belicza.andras.util.type.BitArray bitArray) {
                    for (int i = currentUnitLinks.size() - 1; i >= 0; i--) {
                        if (i < bitArray.getCount() && bitArray.getBit(i)) currentUnitLinks.remove(i);
                    }
                } else if (variant != null && !"None".equals(variant)) {
                    currentUnitLinks.clear();
                }

                var subgroups = delta.getAddSubgroups();
                if (subgroups != null) {
                    for (var sg : subgroups) {
                        Integer link = sg.getUnitLink();
                        Integer count = sg.getCount();
                        if (link != null && count != null) {
                            for (int i = 0; i < count; i++) currentUnitLinks.add(link);
                        }
                    }
                }
            } else if (raw instanceof CmdEvent cmd) {
                for (ReplayCommand rc : mapping.process(cmd)) {
                    if (rc instanceof ReplayCommand.IntentCommand ic
                        && ic.intent().intent() instanceof TrainIntent train
                        && train.unitType() == UnitType.MARINE) {

                        int mult = 0;
                        for (Integer link : currentUnitLinks) {
                            if (BARRACKS_UNIT_LINKS.contains(link)) mult++;
                        }
                        mult = Math.max(1, mult);
                        int selSize = currentUnitLinks.size();

                        Map<Integer, Integer> breakdown = new TreeMap<>();
                        for (Integer link : currentUnitLinks) {
                            breakdown.merge(link, 1, Integer::sum);
                        }

                        System.out.printf("  %-8d  %-6d  %-6d  %s%n",
                            cmd.getLoop(), mult, selSize, breakdown);
                    }
                }
            }
        }
    }
}
