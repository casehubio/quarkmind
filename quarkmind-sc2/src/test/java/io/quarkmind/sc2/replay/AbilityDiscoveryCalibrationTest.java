package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import hu.scelight.sc2.rep.model.details.Race;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * Discovers abilLink → unit/building/upgrade mappings by cross-referencing
 * game event commands with tracker events in oracle-restored replays.
 *
 * For each tracker event (UnitBorn at loop T, player P), finds the closest
 * preceding CmdEvent from the same player within a time window. The modal
 * (abilLink, abilCmdIndex) pair across all oracle replays is the mapping.
 *
 * Outputs a complete mapping table for use in extending AbilityMapping.
 */
class AbilityDiscoveryCalibrationTest {

    private static final Path ORACLE_RESTORED = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path ORACLE_INPUT = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/input");

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_RESTORED)) return false;
        try (var s = Files.list(ORACLE_RESTORED)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverUnitTrainAbilLinks() throws Exception {
        var mappings = discoverAll("UnitBorn", ITrackerEvents.ID_UNIT_BORN);
        printMappings("=== Unit Train abilLinks (UnitBorn) ===", mappings);
        assertThat(mappings).as("Must discover unit train abilLinks").isNotEmpty();
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverBuildingPlacementAbilLinks() throws Exception {
        var mappings = discoverAll("UnitInit", ITrackerEvents.ID_UNIT_INIT);
        printMappings("=== Building Placement abilLinks (UnitInit) ===", mappings);
        assertThat(mappings).as("Must discover building placement abilLinks").isNotEmpty();
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverUpgradeResearchAbilLinks() throws Exception {
        Map<String, Map<String, Integer>> mappings = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path strippedPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) continue;
                discoverUpgradesFromReplay(oraclePath, strippedPath, mappings);
            }
        }

        printMappings("=== Upgrade Research abilLinks ===", mappings);
        assertThat(mappings).as("Must discover upgrade abilLinks").isNotEmpty();
    }

    /**
     * Frequency-based upgrade research abilLink discovery. For each upgrade type,
     * collects ALL CmdEvents within the research-time window, excludes known
     * abilLinks (train/morph/build/move), and counts frequency per abilLink.
     * The abilLink that consistently appears before a specific upgrade type
     * (but not generically) is the research command.
     */
    @Test
    @EnabledIf("oracleExists")
    void discoverUpgradeAbilLinksFrequencyBased() throws Exception {
        var knownAbilLinks = Set.of(
                42, 45, 46,       // move, attack, patrol
                129, 147, 149, 151, 155, 159, 160, 161, // Terran build/train
                170, 172, 173, 174, 175, 176, 214,       // Protoss build/train/warp
                183, 184, 186, 193,                       // Zerg build/train
                73, 194, 221, 267, 309, 522, 730,        // morphs
                120, 249, 250, 252,                       // building morphs
                260, 265, 268, 603, 171                   // special abilities
                                   );

        // upgrade type → (abilLink/idx → count)
        Map<String, Map<String, Integer>> upgradeAbilFreq    = new TreeMap<>();
        Map<String, Integer>              upgradeEventCounts = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path strippedPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) {continue;}

                Replay oracle = RepParserEngine.parseReplay(oraclePath, EnumSet.of(RepContent.TRACKER_EVENTS));
                if (oracle == null || oracle.trackerEvents == null) {continue;}

                List<CmdRecord> commands = extractCommands(strippedPath);
                if (commands.isEmpty()) {continue;}

                for (Event raw : oracle.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UPGRADE) {continue;}
                    IUpgradeEvent upgrade  = (IUpgradeEvent) raw;
                    Integer       playerId = upgrade.getPlayerId();
                    if (playerId == null) {continue;}
                    String upgradeName = upgrade.getUpgradeTypeName().toString();
                    long   upgradeLoop = upgrade.getLoop();

                    upgradeEventCounts.merge(upgradeName, 1, Integer::sum);

                    for (CmdRecord cmd : commands) {
                        if (cmd.playerId != playerId) {continue;}
                        long dist = upgradeLoop - cmd.loop;
                        if (dist < 500 || dist > 5000) {continue;}
                        if (knownAbilLinks.contains(cmd.abilLink)) {continue;}
                        String key = cmd.abilLink + "/" + cmd.abilCmdIndex;
                        upgradeAbilFreq.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                                       .merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.println("\n=== Frequency-Based Upgrade Research AbilLink Discovery ===");
        for (var entry : upgradeAbilFreq.entrySet()) {
            String upgradeName = entry.getKey();
            int    totalEvents = upgradeEventCounts.getOrDefault(upgradeName, 0);
            var candidates = entry.getValue().entrySet().stream()
                                  .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                  .limit(5)
                                  .toList();
            if (candidates.isEmpty()) {continue;}
            var    top   = candidates.get(0);
            double ratio = totalEvents > 0 ? (double) top.getValue() / totalEvents : 0;
            System.out.printf("  %-40s events=%d  top=%s (n=%d, ratio=%.1f%%)",
                              upgradeName, totalEvents, top.getKey(), top.getValue(), ratio * 100);
            if (ratio > 0.5) {System.out.print("  ★");}
            System.out.println();
            for (int i = 1; i < candidates.size(); i++) {
                var alt = candidates.get(i);
                System.out.printf("    alt=%s (n=%d)%n", alt.getKey(), alt.getValue());
            }
        }
        System.out.println("\n  ★ = ratio > 50% (strong candidate)");
    }

    /**
     * Enumerates ALL unmapped abilLinks that appear as no-target commands.
     * Research commands don't target a map location — this filters out
     * move/build/warp-in. The remaining no-target abilLinks that aren't
     * already mapped are research candidates.
     */
    @Test
    @EnabledIf("oracleExists")
    void enumerateUnmappedNoTargetAbilLinks() throws Exception {
        var knownAbilLinks = Set.of(
                42, 45, 46, 129, 147, 149, 151, 155, 159, 160, 161,
                170, 172, 173, 174, 175, 176, 214,
                183, 184, 186, 193,
                73, 194, 221, 267, 309, 522, 730,
                120, 249, 250, 252, 260, 265, 268, 603, 171
                                   );

        Map<String, Integer> noTargetAbilFreq = new TreeMap<>();
        final int[]          replayCount      = {0};

        try (var stream = Files.list(ORACLE_INPUT)) {
            for (Path strippedPath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                replayCount[0]++;
                List<CmdRecord> commands = extractCommands(strippedPath);
                for (CmdRecord cmd : commands) {
                    if (cmd.hasTargetPoint) {continue;}
                    if (knownAbilLinks.contains(cmd.abilLink)) {continue;}
                    String key = cmd.abilLink + "/" + cmd.abilCmdIndex;
                    noTargetAbilFreq.merge(key, 1, Integer::sum);
                }
            }
        }

        int totalReplays = replayCount[0];
        System.out.printf("%n=== Unmapped No-Target AbilLinks (%d replays) ===%n", totalReplays);
        System.out.println("  (Research commands have no target point and aren't train/build/morph)");
        noTargetAbilFreq.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(40)
                        .forEach(e -> System.out.printf("  abilLink=%-12s count=%4d  avg=%.1f/replay%n",
                                                        e.getKey(), e.getValue(), (double) e.getValue() / totalReplays));
    }

    /**
     * Direct correlation: for each Upgrade tracker event, find ALL unmapped
     * no-target CmdEvents from the same player in the research-time window.
     * Reports per (upgrade, abilLink) pair the count and modal distance.
     * The real research abilLink will appear at a consistent distance
     * (= research time) across replays.
     */
    @Test
    @EnabledIf("oracleExists")
    void correlateUpgradesWithUnmappedAbilLinks() throws Exception {
        var knownAbilLinks = Set.of(
                42, 45, 46, 129, 147, 149, 151, 155, 159, 160, 161,
                170, 172, 173, 174, 175, 176, 214,
                183, 184, 186, 193,
                73, 194, 221, 267, 309, 522, 730,
                120, 249, 250, 252, 260, 265, 268, 603, 171
                                   );

        // (upgrade, abilLink/idx) → list of distances
        Map<String, Map<String, List<Integer>>> correlations = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path strippedPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) {continue;}

                Replay oracle = RepParserEngine.parseReplay(oraclePath,
                                                            EnumSet.of(RepContent.TRACKER_EVENTS));
                if (oracle == null || oracle.trackerEvents == null) {continue;}

                List<CmdRecord> commands = extractCommands(strippedPath);
                if (commands.isEmpty()) {continue;}

                for (Event raw : oracle.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UPGRADE) {continue;}
                    IUpgradeEvent upgrade  = (IUpgradeEvent) raw;
                    Integer       playerId = upgrade.getPlayerId();
                    if (playerId == null) {continue;}
                    String upgradeName = upgrade.getUpgradeTypeName().toString();
                    long   upgradeLoop = upgrade.getLoop();

                    for (CmdRecord cmd : commands) {
                        if (cmd.playerId != playerId) {continue;}
                        if (cmd.hasTargetPoint) {continue;}
                        if (knownAbilLinks.contains(cmd.abilLink)) {continue;}
                        long dist = upgradeLoop - cmd.loop;
                        if (dist < 200 || dist > 5000) {continue;}
                        String abilKey = cmd.abilLink + "/" + cmd.abilCmdIndex;
                        correlations.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                                    .computeIfAbsent(abilKey, k -> new ArrayList<>())
                                    .add((int) dist);
                    }
                }
            }
        }

        System.out.println("\n=== Upgrade ↔ AbilLink Correlation (modal distance = research time) ===");
        for (var entry : correlations.entrySet()) {
            String upgradeName = entry.getKey();
            var candidates = entry.getValue().entrySet().stream()
                                  .filter(e -> e.getValue().size() >= 2)
                                  .sorted((a, b) -> {
                                      double cvA = cv(a.getValue());
                                      double cvB = cv(b.getValue());
                                      return Double.compare(cvA, cvB);
                                  })
                                  .limit(3)
                                  .toList();
            if (candidates.isEmpty()) {continue;}
            var    best   = candidates.get(0);
            double bestCv = cv(best.getValue());
            int    modal  = modal(best.getValue());
            System.out.printf("  %-40s best=%s  n=%d  modal_dist=%d (%.1fs)  cv=%.2f%s%n",
                              upgradeName, best.getKey(), best.getValue().size(), modal,
                              modal / 22.4, bestCv, bestCv < 0.3 ? "  ★" : "");
            for (int i = 1; i < candidates.size(); i++) {
                var alt = candidates.get(i);
                System.out.printf("    alt=%s  n=%d  modal=%d  cv=%.2f%n",
                                  alt.getKey(), alt.getValue().size(), modal(alt.getValue()), cv(alt.getValue()));
            }
        }
        System.out.println("\n  ★ = CV < 0.3 (tight cluster — likely the research command)");
    }

    private static double cv(List<Integer> values) {
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (mean == 0) {return 999;}
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    private static int modal(List<Integer> values) {
        Map<Integer, Integer> freq = new TreeMap<>();
        for (int v : values) {freq.merge(v, 1, Integer::sum);}
        return freq.entrySet().stream().max(Map.Entry.comparingByValue())
                   .map(Map.Entry::getKey).orElse(0);
    }


    @Test
    @Tag("diagnostic")
    @EnabledIf("oracleExists")
    void discoverMorphAbilLinks() throws Exception {
        Map<String, Map<String, Integer>> mappings = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path strippedPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) {continue;}
                discoverMorphFromReplay(oraclePath, strippedPath, mappings);
            }
        }
        printMappings("=== Morph Unit AbilLinks ===", mappings);
        assertThat(mappings).isNotEmpty();
    }

    @Test
    @Tag("diagnostic")
    @EnabledIf("oracleExists")
    void discoverBuildingMorphAbilLinks() throws Exception {
        Map<String, Map<String, Integer>> mappings       = new TreeMap<>();
        Set<String>                       buildingMorphs = Set.of("OrbitalCommand", "PlanetaryFortress", "Lair", "Hive", "GreaterSpire");

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path strippedPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) {continue;}

                Replay rep = RepParserEngine.parseReplay(oraclePath, EnumSet.of(RepContent.TRACKER_EVENTS));
                if (rep == null || rep.trackerEvents == null) {continue;}
                List<CmdRecord> commands = extractCommands(strippedPath);
                if (commands.isEmpty()) {continue;}

                for (Event raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UNIT_TYPE_CHANGE) {continue;}
                    Object nameObj  = raw.get("unitTypeName");
                    String unitName = nameObj != null ? nameObj.toString() : null;
                    if (unitName == null || !buildingMorphs.contains(unitName)) {continue;}

                    String key = "BuildingMorph:" + unitName;
                    for (CmdRecord cmd : commands) {
                        long dist = raw.getLoop() - cmd.loop;
                        if (dist >= 0 && dist <= 50) {
                            String abilKey = "abilLink=" + cmd.abilLink + ",idx=" + cmd.abilCmdIndex
                                             + (cmd.hasTargetPoint ? ",hasTP" : "") + ",dist=" + dist;
                            mappings.computeIfAbsent(key, k -> new TreeMap<>()).merge(abilKey, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        printMappings("=== Building Morph AbilLinks (0-50 loop window) ===", mappings);
    }

    @Test
    @Tag("diagnostic")
    @EnabledIf("oracleExists")
    void discoverMorphSourceUnitLinks() throws Exception {
        Set<String>                        morphSources   = Set.of("Zergling", "Roach", "Hydralisk", "Corruptor", "Overlord");
        Map<String, Map<Integer, Integer>> unitLinkCounts = new TreeMap<>();
        boolean                            debugged       = false;

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay rep = RepParserEngine.parseReplay(oraclePath, EnumSet.of(RepContent.TRACKER_EVENTS));
                if (rep == null || rep.trackerEvents == null) {continue;}
                for (Event raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UNIT_BORN) {continue;}
                    IBaseUnitEvent ub   = (IBaseUnitEvent) raw;
                    String         name = ub.getUnitTypeName().toString();
                    if (!morphSources.contains(name)) {continue;}
                    if (!debugged) {
                        System.out.println("DEBUG UnitBorn raw params: " + raw.getRawParameters());
                        debugged = true;
                    }
                    Integer unitLink = raw.get("unitLink");
                    if (unitLink == null) {
                        unitLink = raw.get("m_unitLink");
                    }
                    if (unitLink == null) {continue;}
                    unitLinkCounts.computeIfAbsent(name, k -> new TreeMap<>()).merge(unitLink, 1, Integer::sum);
                }
            }
        }

        System.out.println("=== Morph Source UnitLinks ===");
        for (var entry : unitLinkCounts.entrySet()) {
            var best = entry.getValue().entrySet().stream()
                            .max(java.util.Comparator.comparingInt(Map.Entry::getValue)).orElse(null);
            if (best != null) {
                System.out.printf("  %-20s  unitLink=%d  (n=%d)%n", entry.getKey(), best.getKey(), best.getValue());
            }
        }
    }


    private Map<String, Map<String, Integer>> discoverAll(String label, int trackerEventId) throws Exception {
        Map<String, Map<String, Integer>> mappings = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path strippedPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) continue;
                discoverFromReplay(oraclePath, strippedPath, trackerEventId, label, mappings);
            }
        }
        return mappings;
    }

    private void discoverFromReplay(Path oraclePath, Path strippedPath,
                                      int trackerEventId, String label,
                                      Map<String, Map<String, Integer>> mappings) {
        Replay rep = RepParserEngine.parseReplay(oraclePath,
            EnumSet.of(RepContent.TRACKER_EVENTS));
        if (rep == null || rep.trackerEvents == null) return;

        List<CmdRecord> commands = extractCommands(strippedPath);
        if (commands.isEmpty()) return;

        for (Event raw : rep.trackerEvents.getEvents()) {
            if (raw.getId() != trackerEventId) continue;
            IBaseUnitEvent unitEvent = (IBaseUnitEvent) raw;
            Integer ctrlId = unitEvent.getControlPlayerId();
            if (ctrlId == null || ctrlId == 0) continue;
            if (unitEvent.getLoop() == 0) continue;

            String unitName = unitEvent.getUnitTypeName().toString();
            String key = label + ":" + unitName;

            findClosestCommand(commands, ctrlId, unitEvent.getLoop(), key, mappings,
                trackerEventId == ITrackerEvents.ID_UNIT_INIT ? 0 : 100, 1500);
        }
    }

    private void discoverUpgradesFromReplay(Path oraclePath, Path strippedPath,
                                              Map<String, Map<String, Integer>> mappings) {
        Replay rep = RepParserEngine.parseReplay(oraclePath,
            EnumSet.of(RepContent.TRACKER_EVENTS));
        if (rep == null || rep.trackerEvents == null) return;

        List<CmdRecord> commands = extractCommands(strippedPath);
        if (commands.isEmpty()) return;

        for (Event raw : rep.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
            IUpgradeEvent upgrade = (IUpgradeEvent) raw;
            Integer playerId = upgrade.getPlayerId();
            if (playerId == null) continue;

            String upgradeName = upgrade.getUpgradeTypeName().toString();
            String key = "Upgrade:" + upgradeName;

            findClosestCommand(commands, playerId, upgrade.getLoop(), key, mappings,
                500, 5000);
        }
    }

    private void discoverMorphFromReplay(Path oraclePath, Path strippedPath,
                                         Map<String, Map<String, Integer>> mappings) {
        Replay rep = RepParserEngine.parseReplay(oraclePath,
                                                 EnumSet.of(RepContent.TRACKER_EVENTS));
        if (rep == null || rep.trackerEvents == null) {return;}

        List<CmdRecord> commands = extractCommands(strippedPath);
        if (commands.isEmpty()) {return;}

        for (Event raw : rep.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UNIT_TYPE_CHANGE) {continue;}
            if (raw.getLoop() == 0) {continue;}

            Object unitNameObj = raw.get("unitTypeName");
            String unitName    = unitNameObj != null ? unitNameObj.toString() : null;
            if (unitName == null) {continue;}
            if ("Egg".equals(unitName) || "SupplyDepotLowered".equals(unitName)
                || unitName.contains("Flying") || "InvisibleTargetDummy".equals(unitName)) {continue;}

            String key = "MorphUnit:" + unitName;
            findClosestCommandAnyPlayer(commands, raw.getLoop(), key, mappings, 0, 500);
        }
    }


    private List<CmdRecord> extractCommands(Path strippedPath) {
        List<Event> gameEvents;
        try { gameEvents = GameEventStream.events(strippedPath); }
        catch (Exception e) { return List.of(); }

        List<CmdRecord> commands = new ArrayList<>();
        for (Event raw : gameEvents) {
            if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                commands.add(new CmdRecord(
                    cmd.getUserId() + 1,
                    cmd.getLoop(),
                    cmd.getAbilLink(),
                    Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0),
                    cmd.getTargetPoint() != null));
            }
        }
        return commands;
    }

    private void findClosestCommand(List<CmdRecord> commands, int playerId, long trackerLoop,
                                      String trackerKey, Map<String, Map<String, Integer>> mappings,
                                      int minLookback, int maxLookback) {
        CmdRecord best = null;
        long bestDist = Long.MAX_VALUE;
        for (CmdRecord cmd : commands) {
            if (cmd.playerId != playerId) continue;
            long dist = trackerLoop - cmd.loop;
            if (dist >= minLookback && dist <= maxLookback && dist < bestDist) {
                bestDist = dist;
                best = cmd;
            }
        }
        if (best != null) {
            String abilKey = "abilLink=" + best.abilLink + ",idx=" + best.abilCmdIndex
                + (best.hasTargetPoint ? ",hasTP" : "");
            mappings.computeIfAbsent(trackerKey, k -> new TreeMap<>())
                .merge(abilKey, 1, Integer::sum);
        }
    }

    private void findClosestCommandAnyPlayer(List<CmdRecord> commands, long trackerLoop,
                                             String trackerKey, Map<String, Map<String, Integer>> mappings,
                                             int minLookback, int maxLookback) {
        CmdRecord best     = null;
        long      bestDist = Long.MAX_VALUE;
        for (CmdRecord cmd : commands) {
            long dist = trackerLoop - cmd.loop;
            if (dist >= minLookback && dist <= maxLookback && dist < bestDist) {
                bestDist = dist;
                best     = cmd;
            }
        }
        if (best != null) {
            String abilKey = "abilLink=" + best.abilLink + ",idx=" + best.abilCmdIndex
                             + (best.hasTargetPoint ? ",hasTP" : "");
            mappings.computeIfAbsent(trackerKey, k -> new TreeMap<>())
                    .merge(abilKey, 1, Integer::sum);
        }
    }


    private void printMappings(String header, Map<String, Map<String, Integer>> mappings) {
        System.out.println("\n" + header);
        for (var entry : mappings.entrySet()) {
            var sorted = entry.getValue().entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .toList();
            String modal = sorted.isEmpty() ? "?" : sorted.get(0).getKey();
            int modalCount = sorted.isEmpty() ? 0 : sorted.get(0).getValue();
            System.out.printf("  %-40s modal=%-30s (n=%d)%n",
                entry.getKey(), modal, modalCount);
            if (sorted.size() > 1) {
                for (int i = 1; i < Math.min(3, sorted.size()); i++) {
                    System.out.printf("  %-40s  alt=%-30s (n=%d)%n",
                        "", sorted.get(i).getKey(), sorted.get(i).getValue());
                }
            }
        }
    }

    record CmdRecord(int playerId, long loop, int abilLink, int abilCmdIndex, boolean hasTargetPoint) {}

    /**
     * Tight-window upgrade abilLink discovery: uses the known research time (± tolerance loops)
     * per upgrade instead of the generic 200–5000 range. Filters by specific (abilLink, idx)
     * pairs already wired — NOT by abilLink alone — so unwired idx values on known buildings
     * are still discoverable.
     */
    @Test
    @EnabledIf("oracleExists")
    void tightWindowUpgradeDiscovery() throws Exception {
        var targetUpgrades = Set.of(
            "Burrow", "DrillClaws",
            "MedivacIncreaseSpeedBoost", "PersonalCloaking",
            "TerranInfantryWeaponsLevel2",
            "DarkTemplarBlinkUpgrade", "PhoenixRangeUpgrade"
        );

        // Exclude specific (abilLink, idx) pairs already wired — NOT whole abilLinks.
        // Format: "abilLink/idx"
        var wiredPairs = Set.of(
            // Movement/Smart
            "42/0", "45/0", "46/0",
            // Train: Protoss
            "172/0", "172/1", "172/4", "172/5", "172/6",
            "173/0", "173/2", "173/4", "173/8", "173/9",
            "174/0", "174/1", "174/2", "174/3", "174/18",
            "175/0", "176/0",
            // Train: Zerg
            "193/0", "193/1", "193/2", "193/3", "193/4", "193/6", "193/9", "193/10", "193/11", "193/12", "193/14",
            "184/1", "186/0",
            // Train: Terran
            "155/0", "159/0", "159/1", "159/2", "159/3",
            "160/1", "160/4", "160/5", "160/6", "160/7", "160/24",
            "161/0", "161/1", "161/2", "161/3", "161/4", "161/6",
            // Build
            "129/0", "129/1", "129/2", "129/3", "129/4", "129/5", "129/6", "129/8", "129/9", "129/10", "129/11", "129/13", "129/15",
            "170/0", "170/1", "170/2", "170/3", "170/4", "170/5", "170/6", "170/7", "170/9", "170/10", "170/11", "170/12", "170/13", "170/14", "170/15",
            "183/0", "183/2", "183/3", "183/4", "183/5", "183/6", "183/7", "183/8", "183/9", "183/10", "183/11", "183/13", "183/14", "183/15",
            "147/0", "147/1", "149/0", "149/1", "151/0", "151/1",
            // Warp-in
            "214/0", "214/1", "214/3", "214/4", "214/5", "214/6",
            // Morph
            "73/0", "194/0", "221/0", "267/0", "309/0", "522/0", "730/0",
            "120/0", "120/1",
            "249/0", "250/0", "252/0",
            // Upgrades already wired
            "162/0", "162/1", "162/2", "162/3", "162/4", "162/7", "162/8",
            "165/0", "152/0",
            "167/0", "167/3", "167/9", "167/15",
            "166/1", "166/6",
            "169/5", "169/6", "169/7", "169/11", "169/12", "169/13", "169/14", "169/15", "169/16",
            "235/0", "148/0", "124/1",
            "224/0",
            "107/1", "107/2",
            "185/0", "185/1", "185/2", "185/3", "185/4", "185/5", "185/6", "185/7", "185/8",
            "192/0", "192/1", "192/2", "192/3", "192/4", "192/5",
            "262/0", "310/0", "117/0", "117/2",
            "190/0", "190/1", "189/1",
            "180/0", "180/1", "180/2", "180/3", "180/4", "180/5", "180/6", "180/7", "180/8",
            "236/0", "236/1", "236/2", "236/3", "236/4", "236/5", "236/6",
            "547/0", "237/1", "237/2", "182/4", "181/5",
            "223/0", "223/2", "223/3",
            // Misc
            "260/0", "265/0", "268/0", "603/0", "171/0"
        );

        Map<String, Integer> researchTimes = new java.util.HashMap<>();
        for (var ut : io.quarkmind.domain.UpgradeType.values()) {
            researchTimes.put(ut.pythonName(),
                io.quarkmind.domain.SC2Data.upgradeTimeInLoops(ut));
        }

        Map<String, Map<String, List<Integer>>> correlations = new TreeMap<>();
        Map<String, Integer> eventCounts = new TreeMap<>();

        try (var stream = Files.list(ORACLE_RESTORED)) {
            for (Path oraclePath : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Path strippedPath = ORACLE_INPUT.resolve(oraclePath.getFileName());
                if (!Files.exists(strippedPath)) {continue;}

                var oracle = RepParserEngine.parseReplay(oraclePath,
                    EnumSet.of(RepContent.TRACKER_EVENTS));
                if (oracle == null || oracle.trackerEvents == null) {continue;}

                List<CmdRecord> commands = extractCommands(strippedPath);
                if (commands.isEmpty()) {continue;}

                for (Event raw : oracle.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UPGRADE) {continue;}
                    IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                    Integer playerId = upgrade.getPlayerId();
                    if (playerId == null) {continue;}
                    String upgradeName = upgrade.getUpgradeTypeName().toString();
                    if (!targetUpgrades.isEmpty() && !targetUpgrades.contains(upgradeName)) {continue;}

                    eventCounts.merge(upgradeName, 1, Integer::sum);

                    Integer researchTime = researchTimes.get(upgradeName);
                    if (researchTime == null) {continue;}
                    long upgradeLoop = upgrade.getLoop();

                    for (CmdRecord cmd : commands) {
                        if (cmd.playerId != playerId) {continue;}
                        String pairKey = cmd.abilLink + "/" + cmd.abilCmdIndex
                            + (cmd.hasTargetPoint ? ",TP" : "");
                        String filterKey = cmd.abilLink + "/" + cmd.abilCmdIndex;
                        if (wiredPairs.contains(filterKey)) {continue;}
                        long dist = upgradeLoop - cmd.loop;
                        if (Math.abs(dist - researchTime) > 150) {continue;}
                        correlations.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                            .computeIfAbsent(pairKey, k -> new ArrayList<>())
                            .add((int) dist);
                    }
                }
            }
        }

        System.out.println("\n=== Tight-Window Upgrade Discovery (pair-filtered, researchTime ± 100 loops) ===");
        for (String upgradeName : targetUpgrades.stream().sorted().toList()) {
            int events = eventCounts.getOrDefault(upgradeName, 0);
            Integer rt = researchTimes.get(upgradeName);
            System.out.printf("\n  %-35s events=%d  researchTime=%d (%.1fs)%n",
                upgradeName, events, rt != null ? rt : 0,
                rt != null ? rt / 22.4 : 0);

            var candidates = correlations.getOrDefault(upgradeName, Map.of());
            if (candidates.isEmpty()) {
                System.out.println("    → no candidates found");
                continue;
            }
            candidates.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(5)
                .forEach(e -> {
                    var dists = e.getValue();
                    double mean = dists.stream().mapToInt(Integer::intValue).average().orElse(0);
                    System.out.printf("    %s  n=%d  mean_dist=%.0f  cv=%.3f%n",
                        e.getKey(), dists.size(), mean, cv(dists));
                });
        }
        System.out.println();
    }

    @Test
    @EnabledIf("oracleExists")
    void scanAllReplaysForMissingUpgrades() throws Exception {
        Set<String> targetUpgrades = Set.of(
            "Charge", "ShieldWall", "EvolveMuscularAugments",
            "BlinkTech", "EvolveGroovedSpines", "ExtendedThermalLance",
            "PsiStormTech", "Stimpack", "PunisherGrenades",
            "ProtossGroundWeaponsLevel1", "WarpGateResearch",
            "TerranInfantryWeaponsLevel1", "zerglingmovementspeed",
            "GlialReconstitution", "CentrificalHooks"
        );

        var wiredPairs = Set.<String>of();

        Map<String, Integer> researchTimes = new java.util.HashMap<>();
        for (var ut : io.quarkmind.domain.UpgradeType.values()) {
            researchTimes.put(ut.pythonName(), io.quarkmind.domain.SC2Data.upgradeTimeInLoops(ut));
        }

        List<Path> allReplays = new ArrayList<>();
        try (var s = Files.list(ORACLE_RESTORED)) {
            s.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(allReplays::add);
        }
        Path dataRoot = Path.of("../quarkmind-classifier/data/replay_packs");
        for (String dir : List.of("2025_HomeStory_Cup_XXVII", "2026_HomeStory_Cup_XXVIII", "2026_HomeStory_Cup_XXIX")) {
            Path tdir = dataRoot.resolve(dir);
            if (Files.isDirectory(tdir)) {
                try (var walk = Files.walk(tdir)) {
                    walk.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(allReplays::add);
                }
            }
        }
        Path aiarena = Path.of("quarkmind-sc2/replays/aiarena_protoss");
        if (Files.isDirectory(aiarena)) {
            try (var s = Files.list(aiarena)) {
                s.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(allReplays::add);
            }
        }

        System.out.printf("Scanning %d full replays for %d missing upgrades...%n", allReplays.size(), targetUpgrades.size());

        Map<String, Map<String, List<Integer>>> correlations = new TreeMap<>();
        Map<String, Integer> eventCounts = new TreeMap<>();

        for (Path replayPath : allReplays.stream().sorted().toList()) {
            Replay rep;
            try {
                rep = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
            } catch (Exception e) { continue; }
            if (rep == null || rep.trackerEvents == null || rep.gameEvents == null) {continue;}

            record FullCmd(int playerId, long loop, int abilLink, int abilCmdIndex, String dataVariant) {}
            List<FullCmd> commands = new ArrayList<>();
            for (var raw : rep.gameEvents.getEvents()) {
                if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                    var data = cmd.getData();
                    String variant = data != null ? data.value1 : "none";
                    commands.add(new FullCmd(cmd.getUserId() + 1, cmd.getLoop(), cmd.getAbilLink(),
                        Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0), variant));
                }
            }
            if (commands.isEmpty()) {continue;}

            for (var raw : rep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) {continue;}
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                Integer playerId = upgrade.getPlayerId();
                if (playerId == null) {continue;}
                String upgradeName = upgrade.getUpgradeTypeName().toString();
                if (!targetUpgrades.isEmpty() && !targetUpgrades.contains(upgradeName)) {continue;}

                eventCounts.merge(upgradeName, 1, Integer::sum);
                Integer researchTime = researchTimes.get(upgradeName);
                if (researchTime == null) {continue;}

                for (FullCmd cmd : commands) {
                    if (cmd.playerId != playerId) {continue;}
                    String pairKey = cmd.abilLink + "/" + cmd.abilCmdIndex + "[" + cmd.dataVariant + "]";
                    long dist = upgrade.getLoop() - cmd.loop;
                    if (Math.abs(dist - researchTime) > 200) {continue;}
                    correlations.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                        .computeIfAbsent(pairKey, k -> new ArrayList<>()).add((int) dist);
                }
            }
        }

        System.out.println("\n=== All upgrade event counts ===");
        eventCounts.entrySet().stream().sorted((a,b) -> b.getValue() - a.getValue())
            .forEach(e -> System.out.printf("  %-40s events=%d%n", e.getKey(), e.getValue()));

        System.out.println("\n=== Correlation results ===");
        for (String name : correlations.keySet().stream().sorted().toList()) {
            int events = eventCounts.getOrDefault(name, 0);
            Integer rt = researchTimes.get(name);
            System.out.printf("\n  %-35s events=%d  researchTime=%d (%.1fs)%n", name, events, rt != null ? rt : 0, rt != null ? rt / 22.4 : 0);
            var candidates = correlations.getOrDefault(name, Map.of());
            if (candidates.isEmpty()) { System.out.println("    → no candidates found"); continue; }
            candidates.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size()).limit(10).forEach(e -> {
                var dists = e.getValue();
                System.out.printf("    %s  n=%d  mean_dist=%.0f  cv=%.3f%n", e.getKey(), dists.size(),
                    dists.stream().mapToInt(Integer::intValue).average().orElse(0), cv(dists));
            });
        }
        System.out.println();
    }

    @Test
    @EnabledIf("oracleExists")
    void enumerateUnknownAbilLinksByRace() throws Exception {
        var known = Set.of(42,45,46,129,147,149,151,155,159,160,161,170,172,173,174,175,176,183,184,186,193,214,267,73,194,221,309,522,730,120,249,250,252,260,265,268,603,171,162,164,165,152,167,166,169,235,148,124,224,107,185,192,262,310,117,190,189,180,236,547,237,182,181,223);

        List<Path> replays = new ArrayList<>();
        try (var s = Files.list(ORACLE_RESTORED)) { s.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(replays::add); }
        Path dataRoot = Path.of("../quarkmind-classifier/data/replay_packs");
        for (String d : List.of("2025_HomeStory_Cup_XXVII","2026_HomeStory_Cup_XXVIII","2026_HomeStory_Cup_XXIX")) {
            Path dp = dataRoot.resolve(d);
            if (Files.isDirectory(dp)) { try (var w = Files.walk(dp)) { w.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(replays::add); } }
        }
        Path ai = Path.of("quarkmind-sc2/replays/aiarena_protoss");
        if (Files.isDirectory(ai)) { try (var s = Files.list(ai)) { s.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(replays::add); } }

        Map<String, Map<String, Integer>> raceAbilLinks = new TreeMap<>();

        for (Path rp : replays.stream().sorted().toList()) {
            Replay rep;
            try { rep = RepParserEngine.parseReplay(rp, EnumSet.of(RepContent.DETAILS, RepContent.GAME_EVENTS)); }
            catch (Exception e) { continue; }
            if (rep == null || rep.details == null || rep.gameEvents == null) {continue;}

            Map<Integer, String> playerRace = new HashMap<>();
            var players = rep.details.getPlayerList();
            for (int i = 0; i < players.length; i++) {
                if (players[i].getRace() != null) {playerRace.put(i, players[i].getRace().toString());}
            }

            for (var raw : rep.gameEvents.getEvents()) {
                if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                    int link = cmd.getAbilLink();
                    if (known.contains(link)) {continue;}
                    if (cmd.getData() != null) {continue;}
                    String race = playerRace.getOrDefault(cmd.getUserId(), "Unknown");
                    int idx = Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0);
                    String key = link + "/" + idx;
                    raceAbilLinks.computeIfAbsent(race, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
                }
            }
        }

        System.out.printf("\nScanned %d replays for unknown no-target abilLinks by race%n", replays.size());
        for (var entry : raceAbilLinks.entrySet()) {
            System.out.println("\n" + entry.getKey() + ":");
            entry.getValue().entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(40)
                .forEach(e -> System.out.printf("  %s  count=%d%n", e.getKey(), e.getValue()));
        }
    }

    /**
     * End-to-end validation: process full replays through AbilityMapping and compare
     * detected UpgradeCommands against oracle tracker UpgradeEvents. Measures the
     * actual detection accuracy — not just "is the abilLink wired" but "does it fire
     * correctly in real replays with real selection state and event ordering."
     */
    @Test
    @EnabledIf("oracleExists")
    void validateUpgradeDetectionAccuracy() throws Exception {
        Set<String> trackedUpgrades = new java.util.HashSet<>();
        for (var ut : io.quarkmind.domain.UpgradeType.values()) {
            trackedUpgrades.add(ut.pythonName());
        }

        List<Path> replays = new ArrayList<>();
        try (var s = Files.list(ORACLE_RESTORED)) {
            s.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(replays::add);
        }
        Path dataRoot = Path.of("../quarkmind-classifier/data/replay_packs");
        for (String d : List.of("2025_HomeStory_Cup_XXVII", "2026_HomeStory_Cup_XXVIII", "2026_HomeStory_Cup_XXIX")) {
            Path dp = dataRoot.resolve(d);
            if (Files.isDirectory(dp)) {
                try (var w = Files.walk(dp)) { w.filter(p -> p.toString().endsWith(".SC2Replay")).forEach(replays::add); }
            }
        }

        int totalOracle = 0;
        int totalDetected = 0;
        Map<String, int[]> perUpgrade = new TreeMap<>();

        for (Path rp : replays.stream().sorted().toList()) {
            Replay rep;
            try {
                rep = RepParserEngine.parseReplay(rp, EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
            } catch (Exception e) { continue; }
            if (rep == null || rep.details == null || rep.trackerEvents == null || rep.gameEvents == null) {continue;}

            var players = rep.details.getPlayerList();
            Map<Integer, Race> playerRaces = new HashMap<>();
            for (int i = 0; i < players.length; i++) {
                if (players[i].getRace() != null) {playerRaces.put(i, players[i].getRace());}
            }

            AbilityProfile profile = AbilityProfile.resolve(
                    rep.header != null && rep.header.baseBuild != null ? rep.header.baseBuild : 75689);
            Map<Integer, AbilityMapping> mappings = new HashMap<>();
            for (var entry : playerRaces.entrySet()) {
                mappings.put(entry.getKey(), new AbilityMapping(entry.getKey() + 1, true, entry.getValue(), profile));
            }

            Map<Integer, Set<String>> detectedUpgrades = new HashMap<>();

            for (var raw : rep.gameEvents.getEvents()) {
                if (raw instanceof hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent sel) {
                    for (var m : mappings.values()) { m.onSelection(sel); }
                } else if (raw instanceof CmdEvent cmd) {
                    for (var m : mappings.values()) {
                        for (var result : m.process(cmd)) {
                            if (result instanceof ReplayCommand.UpgradeCommand uc) {
                                detectedUpgrades.computeIfAbsent(cmd.getUserId(), k -> new java.util.HashSet<>())
                                    .add(uc.upgradeName());
                            }
                        }
                    }
                }
            }

            for (var raw : rep.trackerEvents.getEvents()) {
                if (raw.getId() != ITrackerEvents.ID_UPGRADE) {continue;}
                IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                if (upgrade.getPlayerId() == null) {continue;}
                String name = upgrade.getUpgradeTypeName().toString();
                if (!trackedUpgrades.contains(name)) {continue;}

                int userId = upgrade.getPlayerId() - 1;
                totalOracle++;
                int[] counts = perUpgrade.computeIfAbsent(name, k -> new int[2]);
                counts[0]++;
                if (detectedUpgrades.getOrDefault(userId, Set.of()).contains(name)) {
                    totalDetected++;
                    counts[1]++;
                }
            }
        }

        double accuracy = totalOracle > 0 ? 100.0 * totalDetected / totalOracle : 0;
        System.out.printf("%n=== Upgrade Detection Accuracy ===%n");
        System.out.printf("  Replays: %d%n", replays.size());
        System.out.printf("  Oracle events: %d  Detected: %d  Accuracy: %.1f%%%n%n", totalOracle, totalDetected, accuracy);

        System.out.println("  Per-upgrade breakdown (missed only):");
        int missedCount = 0;
        for (var entry : perUpgrade.entrySet()) {
            int oracle = entry.getValue()[0];
            int detected = entry.getValue()[1];
            if (detected < oracle) {
                System.out.printf("    %-40s %d/%d (%.0f%%)%n", entry.getKey(), detected, oracle,
                    100.0 * detected / oracle);
                missedCount++;
            }
        }
        if (missedCount == 0) { System.out.println("    (none — all upgrades detected)"); }

        System.out.printf("%n  Per-upgrade breakdown (all):%n");
        for (var entry : perUpgrade.entrySet()) {
            int oracle = entry.getValue()[0];
            int detected = entry.getValue()[1];
            System.out.printf("    %-40s %d/%d%n", entry.getKey(), detected, oracle);
        }

        assertThat(accuracy).as("Upgrade detection accuracy").isGreaterThan(70.0);
    }
}
