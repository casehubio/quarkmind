package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
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
 * Cross-references game event CmdEvents with oracle tracker events to discover
 * the correct abilLink/abilCmdIndex → UnitType mappings for 4.9.3 human replays.
 *
 * For each target abilLink, finds the closest UnitBorn tracker event after each
 * command and builds a histogram of (abilCmdIndex → unitTypeName → count).
 */
class LarvaTrainDiscoveryTest {

    private static final Path ORACLE_RESTORED = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path ORACLE_INPUT = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/input");

    private static final Set<String> LARVA_UNITS = Set.of(
        "Drone", "Zergling", "Roach", "Hydralisk", "Mutalisk", "Overlord",
        "Corruptor", "Infestor", "SwarmHostMP", "Ultralisk", "Viper");

    private static final Set<String> GATEWAY_UNITS = Set.of(
        "Zealot", "Stalker", "Sentry", "Adept", "HighTemplar", "DarkTemplar");

    private static final Set<String> ROBOTICS_UNITS = Set.of(
        "Immortal", "Observer", "Colossus", "Disruptor", "WarpPrism");

    private static final Set<String> STARGATE_PROTO_UNITS = Set.of(
        "Phoenix", "VoidRay", "Oracle", "Carrier", "Tempest", "Mothership");

    private static final Set<String> BARRACKS_UNITS = Set.of(
        "Marine", "Marauder", "Reaper", "Ghost");

    private static final Set<String> FACTORY_UNITS = Set.of(
        "Hellion", "HellionTank", "SiegeTank", "Cyclone", "Thor", "WidowMine");

    private static final Set<String> STARPORT_UNITS = Set.of(
        "Medivac", "VikingFighter", "Liberator", "Banshee", "Raven", "Battlecruiser");

    private static final Set<String> NEXUS_UNITS = Set.of("Probe");

    private static final Set<String> COMMAND_CENTER_UNITS = Set.of("SCV");

    private static final Set<String> HATCHERY_UNITS = Set.of("Queen");

    static boolean oracleAndInputExist() {
        if (!Files.isDirectory(ORACLE_RESTORED) || !Files.isDirectory(ORACLE_INPUT)) return false;
        try (var s = Files.list(ORACLE_RESTORED)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleAndInputExist")
    void discoverAllTrainAbilLinkMappings() throws Exception {
        // abilLink → (idx → unitName → count)
        Map<Integer, Map<Integer, Map<String, Integer>>> allMappings = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path inputPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(inputPath)) continue;
                processReplay(oraclePath, inputPath, allMappings);
            }
        }

        int[] targetAbilLinks = {193, 160, 135, 159, 173, 175, 172, 184, 185};
        String[] targetNames = {"Larva (193)", "Factory (160)", "Starport (135)",
            "Barracks (159)", "Robotics (173)", "Nexus (175)", "Gateway (172)",
            "Hatchery (184)", "Unknown-185 (185)"};
        Set<String>[] unitSets = new Set[]{LARVA_UNITS, FACTORY_UNITS, STARPORT_UNITS,
            BARRACKS_UNITS, ROBOTICS_UNITS, NEXUS_UNITS, GATEWAY_UNITS,
            HATCHERY_UNITS, LARVA_UNITS};

        for (int i = 0; i < targetAbilLinks.length; i++) {
            int abilLink = targetAbilLinks[i];
            System.out.printf("%n=== %s abilLink=%d ===%n", targetNames[i], abilLink);
            Map<Integer, Map<String, Integer>> idxMap = allMappings.getOrDefault(abilLink, Map.of());
            if (idxMap.isEmpty()) {
                System.out.println("  (no matches found)");
                continue;
            }

            for (var idxEntry : new TreeMap<>(idxMap).entrySet()) {
                int idx = idxEntry.getKey();
                var unitCounts = idxEntry.getValue();
                var sorted = unitCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .toList();
                System.out.printf("  idx=%d:%n", idx);
                for (var uc : sorted) {
                    System.out.printf("    %-25s n=%d%n", uc.getKey(), uc.getValue());
                }
            }

            System.out.printf("%n  PROPOSED MAPPING for abilLink=%d:%n", abilLink);
            for (var idxEntry : new TreeMap<>(idxMap).entrySet()) {
                int idx = idxEntry.getKey();
                var sorted = idxEntry.getValue().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .toList();
                if (!sorted.isEmpty()) {
                    System.out.printf("    idx=%d → %s (n=%d)%n",
                        idx, sorted.get(0).getKey(), sorted.get(0).getValue());
                }
            }
        }

        // Also print all OTHER abilLinks that matched, for discovery
        System.out.println("\n=== Other abilLinks with matches ===");
        Set<Integer> known = new HashSet<>();
        for (int a : targetAbilLinks) known.add(a);
        for (var entry : allMappings.entrySet()) {
            if (known.contains(entry.getKey())) continue;
            int abilLink = entry.getKey();
            int total = entry.getValue().values().stream()
                .mapToInt(m -> m.values().stream().mapToInt(Integer::intValue).sum()).sum();
            if (total < 5) continue;
            System.out.printf("  abilLink=%d (total=%d):%n", abilLink, total);
            for (var idxEntry : new TreeMap<>(entry.getValue()).entrySet()) {
                var sorted = idxEntry.getValue().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .toList();
                if (!sorted.isEmpty()) {
                    System.out.printf("    idx=%d → %s (n=%d)%n",
                        idxEntry.getKey(), sorted.get(0).getKey(), sorted.get(0).getValue());
                }
            }
        }

        assertThat(allMappings).as("Must discover abilLink mappings").isNotEmpty();
    }

    private void processReplay(Path oraclePath, Path inputPath,
                                Map<Integer, Map<Integer, Map<String, Integer>>> allMappings) {
        Replay oracleRep = RepParserEngine.parseReplay(oraclePath,
            EnumSet.of(RepContent.TRACKER_EVENTS));
        if (oracleRep == null || oracleRep.trackerEvents == null) return;

        List<Event> gameEvents;
        try {
            gameEvents = GameEventStream.events(inputPath);
        } catch (Exception e) { return; }

        // All trainable unit types we care about
        Set<String> allTrainableUnits = new HashSet<>();
        allTrainableUnits.addAll(LARVA_UNITS);
        allTrainableUnits.addAll(GATEWAY_UNITS);
        allTrainableUnits.addAll(ROBOTICS_UNITS);
        allTrainableUnits.addAll(STARGATE_PROTO_UNITS);
        allTrainableUnits.addAll(BARRACKS_UNITS);
        allTrainableUnits.addAll(FACTORY_UNITS);
        allTrainableUnits.addAll(STARPORT_UNITS);
        allTrainableUnits.addAll(NEXUS_UNITS);
        allTrainableUnits.addAll(COMMAND_CENTER_UNITS);
        allTrainableUnits.addAll(HATCHERY_UNITS);

        // Collect UnitBorn events per player, sorted by loop
        Map<Integer, List<long[]>> unitBornByPlayer = new HashMap<>();
        Map<Long, String> unitBornNameByKey = new HashMap<>();

        for (Event raw : oracleRep.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UNIT_BORN) continue;
            IBaseUnitEvent born = (IBaseUnitEvent) raw;
            Integer pid = born.getControlPlayerId();
            if (pid == null || pid == 0) continue;
            if (born.getLoop() == 0) continue;
            String name = born.getUnitTypeName().toString();
            if (!allTrainableUnits.contains(name)) continue;

            long uniqueKey = born.getLoop() * 100_000L + pid * 10_000L
                + unitBornByPlayer.computeIfAbsent(pid, k -> new ArrayList<>()).size();
            unitBornByPlayer.get(pid).add(new long[]{born.getLoop(), uniqueKey});
            unitBornNameByKey.put(uniqueKey, name);
        }

        // Sort UnitBorn lists by loop
        for (var list : unitBornByPlayer.values()) {
            list.sort(Comparator.comparingLong(a -> a[0]));
        }

        // For each CmdEvent, find the closest subsequent UnitBorn
        Set<Long> claimed = new HashSet<>();
        for (Event raw : gameEvents) {
            if (!(raw instanceof CmdEvent cmd)) continue;
            Integer abilLink = cmd.getAbilLink();
            if (abilLink == null) continue;
            int idx = cmd.getAbilCmdIndex() != null ? cmd.getAbilCmdIndex() : 0;
            int playerId = cmd.getUserId() + 1;
            long cmdLoop = cmd.getLoop();

            List<long[]> births = unitBornByPlayer.get(playerId);
            if (births == null) continue;

            // Find first unclaimed UnitBorn in window [cmdLoop+50, cmdLoop+1500]
            long[] bestBirth = null;
            for (long[] birth : births) {
                long dist = birth[0] - cmdLoop;
                if (dist < 50) continue;
                if (dist > 1500) break;
                if (claimed.contains(birth[1])) continue;
                bestBirth = birth;
                break;
            }

            if (bestBirth != null) {
                String unitName = unitBornNameByKey.get(bestBirth[1]);
                if (unitName != null) {
                    claimed.add(bestBirth[1]);
                    allMappings.computeIfAbsent(abilLink, k -> new TreeMap<>())
                        .computeIfAbsent(idx, k -> new TreeMap<>())
                        .merge(unitName, 1, Integer::sum);
                }
            }
        }
    }
}
