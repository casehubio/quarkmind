package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class TournamentMorphDiscoveryTest {

    private static final Path DIR = Path.of("../quarkmind-classifier/data/replay_packs/2025_HomeStory_Cup_XXVII");
    static boolean exists() { return Files.isDirectory(DIR); }

    @Test @EnabledIf("exists")
    void nearestCmdBeforeMorphTypeChange() throws Exception {
        for (String target : List.of("Baneling", "Ravager", "BroodLord", "Overseer")) {
            findNearest(target);
        }
    }

    private void findNearest(String target) throws Exception {
        Map<String, Integer> nearest = new TreeMap<>();
        Map<String, List<Integer>> dists = new TreeMap<>();
        try (var stream = Files.walk(DIR)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay replay = RepParserEngine.parseReplay(rp, EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                if (replay == null || replay.trackerEvents == null || replay.details == null) continue;
                Player[] players = replay.details.getPlayerList();
                if (players.length < 2) continue;
                List<Integer> zIds = new ArrayList<>();
                for (int i = 0; i < Math.min(players.length, 2); i++) if (players[i].getRace() == Race.ZERG) zIds.add(i);
                if (zIds.isEmpty()) continue;
                List<long[]> cmds = new ArrayList<>();
                for (Event ge : replay.gameEvents.getEvents()) {
                    if (ge instanceof CmdEvent cmd && zIds.contains(cmd.getUserId())) {
                        Integer a = cmd.getAbilLink();
                        if (a != null && a > 0) cmds.add(new long[]{ge.getLoop(), a, cmd.getAbilCmdIndex()});
                    }
                }
                for (Event te : replay.trackerEvents.getEvents()) {
                    if (te.getId() != ITrackerEvents.ID_UNIT_TYPE_CHANGE) continue;
                    Object n = te.get("unitTypeName");
                    if (n == null || !target.equals(n.toString())) continue;
                    long cl = te.getLoop();
                    long best = Long.MAX_VALUE;
                    String bk = null;
                    for (long[] c : cmds) {
                        long d = cl - c[0];
                        if (d > 0 && d < best) { best = d; bk = (int)c[1] + "/" + (int)c[2]; }
                    }
                    if (bk != null) {
                        nearest.merge(bk, 1, Integer::sum);
                        dists.computeIfAbsent(bk, k -> new ArrayList<>()).add((int) best);
                    }
                }
            }
        }
        System.out.printf("%n=== Nearest Zerg Cmd before %s typeChange ===%n", target);
        nearest.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(8).forEach(e -> {
            var d = dists.get(e.getKey());
            double mean = d.stream().mapToInt(Integer::intValue).average().orElse(0);
            System.out.printf("  %-10s count=%3d  mean_dist=%.0f%n", e.getKey(), e.getValue(), mean);
        });
    }
}
