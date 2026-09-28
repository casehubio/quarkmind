package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discovers Protoss Stargate, Robotics, and Gateway training abilLinks by
 * reverse correlation: for each Protoss UnitBorn/UnitInit event in the oracle,
 * finds the closest preceding no-targetPoint CmdEvent from the same player.
 */
class ProtossTrainDiscoveryTest {

    private static final Path ORACLE_RESTORED = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path ORACLE_INPUT = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/input");

    private static final Set<String> UNITBORN_TARGETS = Set.of(
        "Phoenix", "VoidRay", "Oracle", "Carrier", "Tempest", "Mothership",
        "Immortal", "Observer", "Colossus", "Disruptor", "WarpPrism",
        "Zealot", "Stalker", "Sentry", "Adept", "HighTemplar", "DarkTemplar",
        "Probe", "Archon");

    private static final Set<String> UNITINIT_TARGETS = Set.of(
        "Zealot", "Stalker", "Sentry", "Adept", "HighTemplar", "DarkTemplar",
        "Archon");

    static boolean oracleAndInputExist() {
        if (!Files.isDirectory(ORACLE_RESTORED) || !Files.isDirectory(ORACLE_INPUT)) return false;
        try (var s = Files.list(ORACLE_RESTORED)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleAndInputExist")
    void discoverProtossTrainAbilLinks() throws Exception {
        Map<String, Map<String, Integer>> bornMappings = new TreeMap<>();
        Map<String, Integer> bornOracleCounts = new TreeMap<>();
        Map<String, Map<String, Integer>> initMappings = new TreeMap<>();
        Map<String, Integer> initOracleCounts = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path inputPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(inputPath)) continue;
                processReplay(oraclePath, inputPath, bornMappings, bornOracleCounts,
                    initMappings, initOracleCounts);
            }
        }

        System.out.println("\n========== UnitBorn (pre-warp-gate / non-gateway units) ==========");
        printMappings(UNITBORN_TARGETS, bornMappings, bornOracleCounts);

        System.out.println("\n========== UnitInit (warp-gated units) ==========");
        printMappings(UNITINIT_TARGETS, initMappings, initOracleCounts);

        assertThat(bornMappings).as("Must discover Protoss UnitBorn abilLinks").isNotEmpty();
    }

    private void printMappings(Set<String> targets, Map<String, Map<String, Integer>> mappings,
                                Map<String, Integer> oracleCounts) {
        for (String unitType : targets.stream().sorted().toList()) {
            int oracleCount = oracleCounts.getOrDefault(unitType, 0);
            Map<String, Integer> abilMap = mappings.getOrDefault(unitType, Map.of());
            System.out.printf("%n=== %s (oracle count: %d, matched: %d) ===%n",
                unitType, oracleCount, abilMap.values().stream().mapToInt(Integer::intValue).sum());
            abilMap.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %s: count=%d%n", e.getKey(), e.getValue()));
        }

        System.out.println("\n--- PROPOSED MAPPING ---");
        for (String unitType : targets.stream().sorted().toList()) {
            Map<String, Integer> abilMap = mappings.getOrDefault(unitType, Map.of());
            if (abilMap.isEmpty()) {
                System.out.printf("  %-20s → (no matches)%n", unitType);
                continue;
            }
            var best = abilMap.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .orElse(null);
            if (best != null) {
                System.out.printf("  %-20s → %s (n=%d)%n", unitType, best.getKey(), best.getValue());
            }
        }
    }

    private void processReplay(Path oraclePath, Path inputPath,
                                Map<String, Map<String, Integer>> bornMappings,
                                Map<String, Integer> bornOracleCounts,
                                Map<String, Map<String, Integer>> initMappings,
                                Map<String, Integer> initOracleCounts) {
        Replay oracleRep = RepParserEngine.parseReplay(oraclePath,
            EnumSet.of(RepContent.TRACKER_EVENTS));
        if (oracleRep == null || oracleRep.trackerEvents == null) return;
        if (oracleRep.details == null) return;

        Player[] players = oracleRep.details.getPlayerList();
        Set<Integer> protossPlayerIds = new HashSet<>();
        for (int i = 0; i < Math.min(players.length, 2); i++) {
            if (players[i].getRace() == Race.PROTOSS) {
                protossPlayerIds.add(i + 1);
            }
        }
        if (protossPlayerIds.isEmpty()) return;

        List<Event> gameEvents;
        try { gameEvents = GameEventStream.events(inputPath); }
        catch (Exception e) { return; }

        Map<Integer, List<CmdRecord>> cmdsByPlayer = new HashMap<>();
        for (Event raw : gameEvents) {
            if (!(raw instanceof CmdEvent cmd)) continue;
            Integer abilLink = cmd.getAbilLink();
            if (abilLink == null) continue;
            int playerId = cmd.getUserId() + 1;
            if (!protossPlayerIds.contains(playerId)) continue;
            if (cmd.getTargetPoint() != null) continue;
            int idx = cmd.getAbilCmdIndex() != null ? cmd.getAbilCmdIndex() : 0;
            cmdsByPlayer.computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(new CmdRecord(cmd.getLoop(), abilLink, idx));
        }
        for (var list : cmdsByPlayer.values()) {
            list.sort(Comparator.comparingLong(CmdRecord::loop));
        }

        for (Event raw : oracleRep.trackerEvents.getEvents()) {
            if (raw.getId() == ITrackerEvents.ID_UNIT_BORN) {
                IBaseUnitEvent born = (IBaseUnitEvent) raw;
                Integer pid = born.getControlPlayerId();
                if (pid == null || !protossPlayerIds.contains(pid)) continue;
                if (born.getLoop() == 0) continue;
                String unitName = born.getUnitTypeName().toString();
                if (!UNITBORN_TARGETS.contains(unitName)) continue;
                bornOracleCounts.merge(unitName, 1, Integer::sum);
                findAndRecord(cmdsByPlayer.get(pid), born.getLoop(), unitName, bornMappings);
            } else if (raw.getId() == ITrackerEvents.ID_UNIT_INIT) {
                IBaseUnitEvent init = (IBaseUnitEvent) raw;
                Integer pid = init.getControlPlayerId();
                if (pid == null || !protossPlayerIds.contains(pid)) continue;
                String unitName = init.getUnitTypeName().toString();
                if (!UNITINIT_TARGETS.contains(unitName)) continue;
                initOracleCounts.merge(unitName, 1, Integer::sum);
                findAndRecord(cmdsByPlayer.get(pid), init.getLoop(), unitName, initMappings);
            }
        }
    }

    private void findAndRecord(List<CmdRecord> cmds, long trackerLoop,
                                String unitName, Map<String, Map<String, Integer>> mappings) {
        if (cmds == null) return;
        CmdRecord best = null;
        long bestDist = Long.MAX_VALUE;
        for (int i = cmds.size() - 1; i >= 0; i--) {
            CmdRecord cmd = cmds.get(i);
            long dist = trackerLoop - cmd.loop;
            if (dist < 0) continue;
            if (dist > 2000) break;
            if (dist < bestDist) {
                bestDist = dist;
                best = cmd;
            }
        }
        if (best != null) {
            String key = "abilLink=" + best.abilLink + ",idx=" + best.idx;
            mappings.computeIfAbsent(unitName, k -> new TreeMap<>())
                .merge(key, 1, Integer::sum);
        }
    }

    record CmdRecord(long loop, int abilLink, int idx) {}
}
