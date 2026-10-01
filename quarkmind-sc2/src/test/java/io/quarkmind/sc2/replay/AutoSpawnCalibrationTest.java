package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibrates auto-spawn timer constants from oracle-restored replays.
 *
 * <ul>
 * <li>Larva spawn interval: inter-birth intervals between non-Inject Larva UnitBorn events</li>
 * <li>Interceptor build time: inter-birth intervals between consecutive Interceptor UnitBorn events</li>
 * <li>Inject Larva abilLink: CmdEvent correlation with Larva birth clusters (3+ at same loop)</li>
 * </ul>
 */
@Tag("diagnostic")
class AutoSpawnCalibrationTest {

    private static final Path ORACLE_RESTORED = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_RESTORED)) return false;
        try (var s = Files.list(ORACLE_RESTORED)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverLarvaSpawnInterval() throws Exception {
        List<Integer> intervals = new ArrayList<>();

        try (var paths = Files.list(ORACLE_RESTORED)) {
            for (Path replayPath : paths.filter(p -> p.toString().endsWith(".SC2Replay")).toList()) {
                Replay replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.TRACKER_EVENTS));
                if (replay == null || replay.trackerEvents == null) continue;

                Map<Long, List<Long>> larvaBornsByPlayer = new HashMap<>();
                for (Event te : replay.trackerEvents.getEvents()) {
                    if (te.getId() == ITrackerEvents.ID_UNIT_BORN) {
                        var ub = (IBaseUnitEvent) te;
                        if ("Larva".equalsIgnoreCase(ub.getUnitTypeName().toString().trim())) {
                            larvaBornsByPlayer.computeIfAbsent((long) ub.getControlPlayerId(), k -> new ArrayList<>())
                                .add((long) te.getLoop());
                        }
                    }
                }

                for (List<Long> loops : larvaBornsByPlayer.values()) {
                    Collections.sort(loops);
                    for (int i = 1; i < loops.size(); i++) {
                        int diff = (int) (loops.get(i) - loops.get(i - 1));
                        if (diff > 50 && diff < 500) {
                            intervals.add(diff);
                        }
                    }
                }
            }
        }

        Map<Integer, Integer> histogram = new TreeMap<>();
        for (int interval : intervals) {
            histogram.merge(interval, 1, Integer::sum);
        }

        System.out.println("=== Larva Spawn Interval Calibration ===");
        System.out.println("Total interval samples: " + intervals.size());
        System.out.println();
        System.out.println("Top intervals (loop count -> occurrences):");
        histogram.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .limit(15)
            .forEach(e -> System.out.printf("  %d loops (%.1fs)  count=%d%n",
                e.getKey(), e.getKey() / 22.4, e.getValue()));

        assertThat(intervals).as("Must find Larva spawn intervals").isNotEmpty();
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverInterceptorBuildTime() throws Exception {
        List<Integer> buildTimes = new ArrayList<>();

        try (var paths = Files.list(ORACLE_RESTORED)) {
            for (Path replayPath : paths.filter(p -> p.toString().endsWith(".SC2Replay")).toList()) {
                Replay replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.TRACKER_EVENTS));
                if (replay == null || replay.trackerEvents == null) continue;

                Map<Long, List<Long>> interceptorBirths = new HashMap<>();
                for (Event te : replay.trackerEvents.getEvents()) {
                    if (te.getId() == ITrackerEvents.ID_UNIT_BORN) {
                        var ub = (IBaseUnitEvent) te;
                        if ("Interceptor".equalsIgnoreCase(ub.getUnitTypeName().toString().trim())) {
                            interceptorBirths.computeIfAbsent((long) ub.getControlPlayerId(), k -> new ArrayList<>())
                                .add((long) te.getLoop());
                        }
                    }
                }

                for (List<Long> births : interceptorBirths.values()) {
                    if (births.size() < 2) continue;
                    Collections.sort(births);
                    for (int i = 1; i < births.size(); i++) {
                        int diff = (int) (births.get(i) - births.get(i - 1));
                        if (diff > 50 && diff < 500) {
                            buildTimes.add(diff);
                        }
                    }
                }
            }
        }

        Map<Integer, Integer> histogram = new TreeMap<>();
        for (int bt : buildTimes) {
            histogram.merge(bt, 1, Integer::sum);
        }

        System.out.println("=== Interceptor Build Time Calibration ===");
        System.out.println("Total build time samples: " + buildTimes.size());
        System.out.println();
        System.out.println("Top build times (loop count -> occurrences):");
        histogram.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .limit(15)
            .forEach(e -> System.out.printf("  %d loops (%.1fs)  count=%d%n",
                e.getKey(), e.getKey() / 22.4, e.getValue()));

        assertThat(buildTimes).as("Must find Interceptor build time samples").isNotEmpty();
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverInjectLarvaAbilLink() throws Exception {
        Map<String, Integer> abilLinkCounts = new TreeMap<>();
        int totalInjectClusters = 0;

        try (var paths = Files.list(ORACLE_RESTORED)) {
            for (Path replayPath : paths.filter(p -> p.toString().endsWith(".SC2Replay")).toList()) {
                Replay replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS));
                if (replay == null || replay.trackerEvents == null) continue;

                Map<Long, Integer> larvaBornCountByLoop = new TreeMap<>();
                for (Event te : replay.trackerEvents.getEvents()) {
                    if (te.getId() == ITrackerEvents.ID_UNIT_BORN) {
                        var ub = (IBaseUnitEvent) te;
                        if ("Larva".equalsIgnoreCase(ub.getUnitTypeName().toString().trim())) {
                            larvaBornCountByLoop.merge((long) te.getLoop(), 1, Integer::sum);
                        }
                    }
                }

                List<Long> injectLoops = new ArrayList<>();
                for (var entry : larvaBornCountByLoop.entrySet()) {
                    if (entry.getValue() >= 3) {
                        injectLoops.add(entry.getKey());
                        totalInjectClusters++;
                    }
                }

                for (long injectLoop : injectLoops) {
                    for (Event ge : replay.gameEvents.getEvents()) {
                        if (ge instanceof CmdEvent cmd
                            && cmd.getLoop() >= injectLoop - 100
                            && cmd.getLoop() <= injectLoop + 10) {
                            Integer abl = cmd.getAbilLink();
                            int idx = cmd.getAbilCmdIndex();
                            if (abl != null && abl > 0) {
                                abilLinkCounts.merge(abl + "/" + idx, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }

        System.out.println("=== Inject Larva AbilLink Discovery ===");
        System.out.println("Total Inject clusters (3+ Larva at same loop): " + totalInjectClusters);
        System.out.println();
        System.out.println("Candidate abilLinks:");
        abilLinkCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> System.out.printf("  %s  count=%d%n", e.getKey(), e.getValue()));

        assertThat(abilLinkCounts).as("Must discover Inject Larva abilLink candidates").isNotEmpty();
    }
}
