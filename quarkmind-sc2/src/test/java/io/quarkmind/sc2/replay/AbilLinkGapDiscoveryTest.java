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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("diagnostic")
class AbilLinkGapDiscoveryTest {

    private static final Path ORACLE_RESTORED = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    private static final int WINDOW = 50;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_RESTORED)) return false;
        try (var s = Files.list(ORACLE_RESTORED)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverAbilLinks() throws Exception {
        var initTargets = List.of("CreepTumor", "SupplyDepot", "Pylon", "MissileTurret");
        var bornTargets = List.of("Baneling");
        Map<String, Map<String, Integer>> allMappings = new TreeMap<>();
        Map<String, Integer> totalEvents = new TreeMap<>();

        List<Path> replays;
        try (var s = Files.list(ORACLE_RESTORED)) {
            replays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        for (Path replayPath : replays) {
            Replay replay = RepParserEngine.parseReplay(replayPath,
                EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS));
            if (replay == null || replay.trackerEvents == null || replay.gameEvents == null) continue;

            var trackerEvents = replay.trackerEvents.getEvents();
            var gameEvents = replay.gameEvents.getEvents();

            for (Event te : trackerEvents) {
                boolean isInit = te.getId() == ITrackerEvents.ID_UNIT_INIT;
                boolean isBorn = te.getId() == ITrackerEvents.ID_UNIT_BORN;
                if (!isInit && !isBorn) continue;
                var ui = (IBaseUnitEvent) te;
                if (ui.getControlPlayerId() == null || ui.getControlPlayerId() == 0) continue;
                String typeName = ui.getUnitTypeName().toString().trim();
                if (isInit && !initTargets.contains(typeName)) continue;
                if (isBorn && !bornTargets.contains(typeName)) continue;

                totalEvents.merge(typeName, 1, Integer::sum);
                long initLoop = te.getLoop();

                for (Event ge : gameEvents) {
                    if (!(ge instanceof CmdEvent cmd)) continue;
                    if (cmd.getLoop() < initLoop - WINDOW || cmd.getLoop() > initLoop + 10) continue;
                    Integer abl = cmd.getAbilLink();
                    if (abl == null || abl <= 0) continue;
                    Integer idxObj = cmd.getAbilCmdIndex();
                    int idx = idxObj != null ? idxObj : 0;
                    String key = abl + "/" + idx;
                    allMappings.computeIfAbsent(typeName, k -> new TreeMap<>())
                        .merge(key, 1, Integer::sum);
                }
            }
        }

        System.out.println("=== AbilLink Gap Discovery ===");
        System.out.printf("Processed %d replays%n%n", replays.size());

        var allTargets = new java.util.ArrayList<>(bornTargets);
        allTargets.addAll(initTargets);
        for (String target : allTargets) {
            int total = totalEvents.getOrDefault(target, 0);
            var mappings = allMappings.getOrDefault(target, Map.of());
            System.out.printf("--- %s (total oracle UnitInit events: %d) ---%n", target, total);
            if (mappings.isEmpty()) {
                System.out.println("  No correlated CmdEvents found");
            } else {
                mappings.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(20)
                    .forEach(e -> System.out.printf("  abilLink/idx=%s  count=%d%n", e.getKey(), e.getValue()));
            }
            System.out.println();
        }
    }
}
