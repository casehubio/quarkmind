package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class WarpInDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path STRIPPED_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final Set<String> GATEWAY_UNITS = Set.of(
        "Zealot", "Stalker", "Sentry", "Adept", "HighTemplar", "DarkTemplar");

    static boolean oracleAndOriginalsExist() {
        if (!Files.isDirectory(ORACLE_DIR) || !Files.isDirectory(STRIPPED_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleAndOriginalsExist")
    void diagnoseWarpInCoverage() throws Exception {
        int totalReplays       = 0;
        int totalOracleGateway = 0;
        int totalAbil214       = 0;
        int totalEvt104        = 0;
        int evt104AfterAbil214 = 0;

        try (var oracleStream = Files.list(ORACLE_DIR)) {
            for (Path oraclePath : oracleStream
                                           .filter(p -> p.toString().endsWith(".SC2Replay"))
                                           .sorted()
                                           .toList()) {

                Path strippedPath = STRIPPED_DIR.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) {continue;}
                totalReplays++;

                Replay oracleRep = RepParserEngine.parseReplay(oraclePath,
                                                               EnumSet.of(RepContent.TRACKER_EVENTS));
                if (oracleRep == null || oracleRep.trackerEvents == null) {continue;}

                var          players        = oracleRep.details.getPlayerList();
                Set<Integer> protossUserIds = new java.util.HashSet<>();
                for (int i = 0; i < Math.min(players.length, 2); i++) {
                    if ("Prot".equals(players[i].getRace().text) || "Protoss".equals(players[i].getRace().text)) {
                        protossUserIds.add(i); // userId is 0-indexed
                    }
                }

                for (Event raw : oracleRep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UNIT_INIT) {continue;}
                    IBaseUnitEvent init = (IBaseUnitEvent) raw;
                    if (init.getControlPlayerId() == null || init.getControlPlayerId() == 0) {continue;}
                    String unitName = init.getUnitTypeName().toString();
                    if (GATEWAY_UNITS.contains(unitName)) {totalOracleGateway++;}
                }

                List<Event> gameEvents;
                try {
                    gameEvents = GameEventStream.events(strippedPath);
                } catch (Exception e) {continue;}

                // Track last CmdEvent abilLink per userId
                Map<Integer, Integer> lastAbilLink = new java.util.HashMap<>();

                for (Event raw : gameEvents) {
                    if (!protossUserIds.contains(raw.getUserId())) {continue;}

                    if (raw instanceof hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent cmd) {
                        Integer abilLink = cmd.getAbilLink();
                        if (abilLink != null) {
                            lastAbilLink.put(raw.getUserId(), abilLink);
                            if (abilLink == 214) {totalAbil214++;}
                        }
                    } else if (raw.getId() == 104) {
                        // SCmdUpdateTargetPointEvent — repeat command at new location
                        totalEvt104++;
                        Integer prevAbil = lastAbilLink.get(raw.getUserId());
                        if (prevAbil != null && prevAbil == 214) {evt104AfterAbil214++;}
                    }
                }
            }
        }

        System.out.println("\n=== CmdUpdateTargetPoint Diagnostic (" + totalReplays + " replays) ===");
        System.out.printf("Oracle UnitInit (gateway units):   %d%n", totalOracleGateway);
        System.out.printf("CmdEvent abilLink=214:             %d%n", totalAbil214);
        System.out.printf("CmdUpdateTargetPointEvent (ID=104):%d%n", totalEvt104);
        System.out.printf("  ... following abilLink=214:       %d%n", evt104AfterAbil214);
        System.out.printf("abilLink=214 + evt104-after-214:   %d (%.0f%% of oracle)%n",
                          totalAbil214 + evt104AfterAbil214,
                          totalOracleGateway > 0 ? 100.0 * (totalAbil214 + evt104AfterAbil214) / totalOracleGateway : 0);

        assertThat(totalReplays).isGreaterThan(0);
    }
}
