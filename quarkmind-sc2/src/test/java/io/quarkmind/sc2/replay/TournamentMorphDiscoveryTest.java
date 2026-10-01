package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
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

@Tag("diagnostic")
class TournamentMorphDiscoveryTest {

    private static final Path TOURNAMENT_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/2025_HomeStory_Cup_XXVII");

    private static final String[] MORPH_TARGETS = {"Baneling", "Ravager", "Lurker", "BroodLord", "Overseer"};

    static boolean tournamentExists() {
        return Files.isDirectory(TOURNAMENT_DIR);
    }

    @Test
    @EnabledIf("tournamentExists")
    void discoverMorphAbilLinksFromTournament() throws Exception {
        for (String target : MORPH_TARGETS) {
            discoverAbilLinksForMorphTarget(target);
        }
    }

    private void discoverAbilLinksForMorphTarget(String target) throws Exception {
        Map<String, Integer> abilLinkFreq = new TreeMap<>();
        int totalTypeChanges = 0;

        try (var stream = Files.walk(TOURNAMENT_DIR)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay replay = RepParserEngine.parseReplay(rp,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                if (replay == null || replay.trackerEvents == null) continue;

                List<Long> changeLoops = new ArrayList<>();
                for (Event raw : replay.trackerEvents.getEvents()) {
                    if (raw.getId() == ITrackerEvents.ID_UNIT_TYPE_CHANGE) {
                        Object nameObj = raw.get("unitTypeName");
                        if (nameObj != null && target.equals(nameObj.toString())) {
                            changeLoops.add((long) raw.getLoop());
                            totalTypeChanges++;
                        }
                    }
                }
                if (changeLoops.isEmpty()) continue;

                for (Event ge : replay.gameEvents.getEvents()) {
                    if (ge instanceof CmdEvent cmd) {
                        Integer abl = cmd.getAbilLink();
                        if (abl == null || abl <= 0) continue;
                        int idx = cmd.getAbilCmdIndex();
                        for (long changeLoop : changeLoops) {
                            int diff = (int) (changeLoop - ge.getLoop());
                            if (diff >= 50 && diff <= 700) {
                                abilLinkFreq.merge(abl + "/" + idx, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("%n=== %s AbilLink Discovery (Tournament, %d typeChanges) ===%n", target, totalTypeChanges);
        if (totalTypeChanges == 0) {
            System.out.println("  No typeChange events found");
            return;
        }
        abilLinkFreq.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> System.out.printf("  %s  count=%d%n", e.getKey(), e.getValue()));
    }
}
