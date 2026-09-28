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
 * Discovers Terran Factory+TechLab and Starport training abilLinks by reverse
 * correlation: for each Terran UnitBorn event in the oracle, finds the closest
 * preceding no-targetPoint CmdEvent from the same player.
 *
 * The no-targetPoint filter eliminates move/attack-move/build commands,
 * leaving only train and upgrade commands as candidates.
 */
class TerranTrainDiscoveryTest {

    private static final Path ORACLE_RESTORED = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path ORACLE_INPUT = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/input");

    private static final Set<String> TARGET_UNITS = Set.of(
        "SiegeTank", "Cyclone", "Thor", "WidowMine", "Ghost",
        "Medivac", "VikingFighter", "Liberator", "Banshee", "Raven", "Battlecruiser",
        "Hellion", "HellionTank", "Marine", "Marauder", "Reaper", "SCV");

    static boolean oracleAndInputExist() {
        if (!Files.isDirectory(ORACLE_RESTORED) || !Files.isDirectory(ORACLE_INPUT)) return false;
        try (var s = Files.list(ORACLE_RESTORED)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleAndInputExist")
    void discoverTerranTrainAbilLinks() throws Exception {
        // unitTypeName → (abilLink:idx → count)
        Map<String, Map<String, Integer>> mappings = new TreeMap<>();
        Map<String, Integer> oracleCounts = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path inputPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(inputPath)) continue;
                processReplay(oraclePath, inputPath, mappings, oracleCounts);
            }
        }

        for (String unitType : TARGET_UNITS.stream().sorted().toList()) {
            int oracleCount = oracleCounts.getOrDefault(unitType, 0);
            Map<String, Integer> abilMap = mappings.getOrDefault(unitType, Map.of());
            System.out.printf("%n=== %s (oracle count: %d, matched: %d) ===%n",
                unitType, oracleCount, abilMap.values().stream().mapToInt(Integer::intValue).sum());
            abilMap.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %s: count=%d%n", e.getKey(), e.getValue()));
        }

        System.out.println("\n=== PROPOSED MAPPING ===");
        for (String unitType : TARGET_UNITS.stream().sorted().toList()) {
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

        assertThat(mappings).as("Must discover Terran train abilLinks").isNotEmpty();
    }

    private void processReplay(Path oraclePath, Path inputPath,
                                Map<String, Map<String, Integer>> mappings,
                                Map<String, Integer> oracleCounts) {
        Replay oracleRep = RepParserEngine.parseReplay(oraclePath,
            EnumSet.of(RepContent.TRACKER_EVENTS));
        if (oracleRep == null || oracleRep.trackerEvents == null) return;
        if (oracleRep.details == null) return;

        Player[] players = oracleRep.details.getPlayerList();
        Set<Integer> terranPlayerIds = new HashSet<>();
        for (int i = 0; i < Math.min(players.length, 2); i++) {
            if (players[i].getRace() == Race.TERRAN) {
                terranPlayerIds.add(i + 1);
            }
        }
        if (terranPlayerIds.isEmpty()) return;

        List<Event> gameEvents;
        try { gameEvents = GameEventStream.events(inputPath); }
        catch (Exception e) { return; }

        // Collect no-targetPoint CmdEvents per player, sorted by loop
        Map<Integer, List<CmdRecord>> cmdsByPlayer = new HashMap<>();
        for (Event raw : gameEvents) {
            if (!(raw instanceof CmdEvent cmd)) continue;
            Integer abilLink = cmd.getAbilLink();
            if (abilLink == null) continue;
            int playerId = cmd.getUserId() + 1;
            if (!terranPlayerIds.contains(playerId)) continue;
            if (cmd.getTargetPoint() != null) continue;
            int idx = cmd.getAbilCmdIndex() != null ? cmd.getAbilCmdIndex() : 0;
            cmdsByPlayer.computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(new CmdRecord(cmd.getLoop(), abilLink, idx));
        }
        for (var list : cmdsByPlayer.values()) {
            list.sort(Comparator.comparingLong(CmdRecord::loop));
        }

        // For each Terran UnitBorn, find closest preceding no-TP CmdEvent
        for (Event raw : oracleRep.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UNIT_BORN) continue;
            IBaseUnitEvent born = (IBaseUnitEvent) raw;
            Integer pid = born.getControlPlayerId();
            if (pid == null || !terranPlayerIds.contains(pid)) continue;
            if (born.getLoop() == 0) continue;
            String unitName = born.getUnitTypeName().toString();
            if (!TARGET_UNITS.contains(unitName)) continue;

            oracleCounts.merge(unitName, 1, Integer::sum);

            List<CmdRecord> cmds = cmdsByPlayer.get(pid);
            if (cmds == null) continue;

            CmdRecord best = null;
            long bestDist = Long.MAX_VALUE;
            for (int i = cmds.size() - 1; i >= 0; i--) {
                CmdRecord cmd = cmds.get(i);
                long dist = born.getLoop() - cmd.loop;
                if (dist < 100) continue;
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
    }

    record CmdRecord(long loop, int abilLink, int idx) {}
}
