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
import java.util.EnumSet;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discovers the MULE calldown abilLink by cross-referencing CmdEvents
 * with tracker UnitBorn("MULE") events in oracle-restored replays.
 *
 * The discovered abilLink is wired into AbilityMapping.dispatchHuman()
 * as ABIL_MULE_CALLDOWN.
 */
@Tag("diagnostic")
class MuleAbilLinkDiscoveryTest {

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
    void discoverMuleCalldownAbilLink() throws Exception {
        Map<String, Map<String, Integer>> mappings = new TreeMap<>();
        int replaysWithMule = 0;
        int totalMuleBorns = 0;

        try (var paths = Files.list(ORACLE_RESTORED)) {
            for (Path replayPath : paths.filter(p -> p.toString().endsWith(".SC2Replay")).toList()) {
                Replay replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS));
                if (replay == null || replay.trackerEvents == null) continue;

                var trackerEvents = replay.trackerEvents.getEvents();
                boolean hasMule = false;
                for (Event te : trackerEvents) {
                    if (te.getId() == ITrackerEvents.ID_UNIT_BORN) {
                        var ub = (IBaseUnitEvent) te;
                        if ("MULE".equalsIgnoreCase(ub.getUnitTypeName().toString().trim())) {
                            hasMule = true;
                            totalMuleBorns++;
                            long birthLoop = te.getLoop();
                            int player = ub.getControlPlayerId();

                            for (Event ge : replay.gameEvents.getEvents()) {
                                if (ge instanceof CmdEvent cmd && cmd.getLoop() >= birthLoop - 50
                                    && cmd.getLoop() <= birthLoop + 10) {
                                    Integer abl = cmd.getAbilLink();
                                    int idx = cmd.getAbilCmdIndex();
                                    if (abl != null && abl > 0) {
                                        String key = abl + "/" + idx;
                                        mappings.computeIfAbsent("MULE", k -> new TreeMap<>())
                                            .merge(key, 1, Integer::sum);
                                    }
                                }
                            }
                        }
                    }
                }
                if (hasMule) replaysWithMule++;
            }
        }

        System.out.println("=== MULE Calldown AbilLink Discovery ===");
        System.out.println("Replays with MULE births: " + replaysWithMule);
        System.out.println("Total MULE UnitBorn events: " + totalMuleBorns);
        System.out.println();
        var muleMappings = mappings.getOrDefault("MULE", Map.of());
        System.out.println("Candidate abilLink/idx (sorted by frequency):");
        muleMappings.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> System.out.printf("  %s  count=%d%n", e.getKey(), e.getValue()));

        assertThat(muleMappings).as("Must discover MULE calldown abilLink").isNotEmpty();
    }
}
