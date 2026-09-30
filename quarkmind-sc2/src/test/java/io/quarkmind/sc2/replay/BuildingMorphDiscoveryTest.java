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
import java.util.Comparator;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discovers building morph abilLinks by cross-referencing UnitTypeChange tracker
 * events with preceding CmdEvents from human ladder replays.
 *
 * Building morphs: CC→OrbitalCommand, CC→PlanetaryFortress,
 * Hatchery→Lair, Lair→Hive, Spire→GreaterSpire.
 *
 * UnitTypeChange lacks controlPlayerId, so we match against ALL players'
 * CmdEvents within a tight time window with no-target constraint.
 */
@Tag("diagnostic")
class BuildingMorphDiscoveryTest {
    private static final Comparator<Map.Entry<String, Integer>> BY_COUNT_DESC = (a, b) -> b.getValue() - a.getValue();


    private static final Path ORACLE_RESTORED = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path AIARENA         = Path.of("replays/aiarena_protoss");
    private static final Path LADDER_4101     = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.10.1/replays");


    private static final Set<String> BUILDING_MORPHS = Set.of(
        "OrbitalCommand", "PlanetaryFortress", "Lair", "Hive", "GreaterSpire");

    private static final Set<Integer> NOISE_ABIL_LINKS = Set.of(42, 45);

    static boolean ladderReplaysExist() {
        return Files.isDirectory(ORACLE_RESTORED) || Files.isDirectory(AIARENA);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void discoverBuildingMorphAbilLinks() throws Exception {
        List<Path> replays = new ArrayList<>();
        if (Files.isDirectory(ORACLE_RESTORED)) {
            try (var stream = Files.list(ORACLE_RESTORED)) {
                replays.addAll(stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList());
            }
        }

        Map<String, Map<String, Integer>> mappings    = new TreeMap<>();
        Map<String, Integer>              totalCounts = new TreeMap<>();
        int                               parsed      = 0;
        int                               printed     = 0;

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                                                     EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.trackerEvents == null || replay.gameEvents == null) {continue;}
            parsed++;

            // Index ALL CmdEvents by loop — including abilLink 42, 45, everything
            Map<Long, List<String>> eventsByLoop = new HashMap<>();
            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                    boolean hasTP = cmd.getTargetPoint() != null;
                    String desc = "Cmd(user=" + cmd.getUserId() + ",abil=" + cmd.getAbilLink()
                                  + ",idx=" + Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0)
                                  + (hasTP ? ",hasTP" : "") + ")";
                    eventsByLoop.computeIfAbsent((long) cmd.getLoop(), k -> new ArrayList<>()).add(desc);
                }
            }

            for (Event raw : replay.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UNIT_TYPE_CHANGE) {continue;}
                if (raw.getLoop() == 0) {continue;}
                Object nameObj  = raw.get("unitTypeName");
                String unitName = nameObj != null ? nameObj.toString() : null;
                if (unitName == null || !BUILDING_MORPHS.contains(unitName)) {continue;}

                totalCounts.merge(unitName, 1, Integer::sum);
                long tcLoop = raw.getLoop();

                // Print first 3 per type with wide window
                if (printed < 20) {
                    printed++;
                    System.out.printf("  [%s] UnitTypeChange at loop %d (replay: %s)%n",
                                      unitName, tcLoop, rp.getFileName());
                    for (long l = tcLoop - 10; l <= tcLoop + 5; l++) {
                        var events = eventsByLoop.get(l);
                        if (events != null) {
                            for (String e : events) {
                                System.out.printf("    loop %d (dist=%d): %s%n", l, tcLoop - l, e);
                            }
                        }
                    }
                }

                // Collect from wider window for stats
                for (long l = tcLoop - 5; l <= tcLoop; l++) {
                    var events = eventsByLoop.get(l);
                    if (events != null) {
                        for (String e : events) {
                            mappings.computeIfAbsent(unitName, k -> new TreeMap<>())
                                    .merge(e, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        System.out.printf("%n=== Building Morph Discovery (wide context, all abilLinks) ===%n");
        System.out.printf("Replays: %d, parsed: %d%n", replays.size(), parsed);
        for (String morph : BUILDING_MORPHS.stream().sorted().toList()) {
            int                  total   = totalCounts.getOrDefault(morph, 0);
            Map<String, Integer> abilMap = mappings.getOrDefault(morph, Map.of());
            int                  matched = abilMap.values().stream().mapToInt(Integer::intValue).sum();
            System.out.printf("%n--- %s (events: %d, matched: %d) ---%n", morph, total, matched);
            abilMap.entrySet().stream()
                   .sorted((a, b) -> b.getValue() - a.getValue())
                   .limit(10)
                   .forEach(e -> System.out.printf("  %s  count=%d%n", e.getKey(), e.getValue()));
        }

        assertThat(parsed).as("Should parse replays").isGreaterThan(0);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void verifyBuildingMorphCandidates() throws Exception {
        // Candidate abilLinks from first discovery pass + SC2 Galaxy Editor data
        // OrbitalCommand: abilLink=120 (CC morph ability)
        // PlanetaryFortress: abilLink=120 (same CC morph ability, different idx)
        // Lair: abilLink=249 (Hatchery morph)
        // Hive: abilLink=250 (Lair morph)
        // GreaterSpire: abilLink=252 (Spire morph)
        Set<Integer> CANDIDATE_ABIL_LINKS = Set.of(120, 249, 250, 252);

        List<Path> replays = new ArrayList<>();
        if (Files.isDirectory(ORACLE_RESTORED)) {
            try (var stream = Files.list(ORACLE_RESTORED)) {
                replays.addAll(stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList());
            }
        }

        // Count occurrences of candidate abilLinks near each building morph type
        Map<String, Map<String, Integer>> mappings    = new TreeMap<>();
        Map<String, Integer>              totalCounts = new TreeMap<>();

        // Also count total occurrences of each candidate abilLink in game events
        Map<Integer, Integer> globalAbilCounts = new TreeMap<>();

        int parsed = 0;
        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                                                     EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.trackerEvents == null || replay.gameEvents == null) {continue;}
            parsed++;

            var cmds = new ArrayList<CmdRecord>();
            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                    int al = cmd.getAbilLink();
                    if (CANDIDATE_ABIL_LINKS.contains(al)) {
                        globalAbilCounts.merge(al, 1, Integer::sum);
                        cmds.add(new CmdRecord(cmd.getLoop(), al,
                                               Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0), false));
                    }
                }
            }
            cmds.sort(Comparator.comparingLong(CmdRecord::loop));

            for (Event raw : replay.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UNIT_TYPE_CHANGE) {continue;}
                if (raw.getLoop() == 0) {continue;}
                Object nameObj  = raw.get("unitTypeName");
                String unitName = nameObj != null ? nameObj.toString() : null;
                if (unitName == null || !BUILDING_MORPHS.contains(unitName)) {continue;}

                totalCounts.merge(unitName, 1, Integer::sum);
                long tcLoop = raw.getLoop();

                for (CmdRecord cmd : cmds) {
                    long dist = tcLoop - cmd.loop;
                    if (dist >= 0 && dist <= 10) {
                        String key = "abilLink=" + cmd.abilLink + ",idx=" + cmd.abilCmdIndex + ",dist=" + dist;
                        mappings.computeIfAbsent(unitName, k -> new TreeMap<>())
                                .merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.printf("%n=== Candidate Building Morph Verification ===%n");
        System.out.printf("Parsed: %d replays%n", parsed);
        System.out.printf("Global abilLink counts: %s%n", globalAbilCounts);
        for (String morph : BUILDING_MORPHS.stream().sorted().toList()) {
            int total   = totalCounts.getOrDefault(morph, 0);
            var abilMap = mappings.getOrDefault(morph, Map.of());
            System.out.printf("%n--- %s (events: %d) ---%n", morph, total);
            abilMap.entrySet().stream()
                   .sorted((a, b) -> b.getValue() - a.getValue())
                   .forEach(e -> System.out.printf("  %s  count=%d%n", e.getKey(), e.getValue()));
        }

        assertThat(parsed).isGreaterThan(0);
    }


    @Test
    @EnabledIf("ladderReplaysExist")
    void checkReplayCapabilities() throws Exception {
        Path[] dirs = {ORACLE_RESTORED, AIARENA, LADDER_4101};
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {continue;}
            try (var stream = Files.list(dir)) {
                Path first = stream.filter(p -> p.toString().endsWith(".SC2Replay")).findFirst().orElse(null);
                if (first == null) {continue;}
                Replay r = RepParserEngine.parseReplay(first,
                                                       EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS));
                System.out.printf("  %-60s  game=%b  tracker=%b%n",
                                  dir, r != null && r.gameEvents != null, r != null && r.trackerEvents != null);
            }
        }
    }


    record CmdRecord(long loop, int abilLink, int abilCmdIndex, boolean hasTP) {}
}
