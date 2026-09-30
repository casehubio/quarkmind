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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
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
}
