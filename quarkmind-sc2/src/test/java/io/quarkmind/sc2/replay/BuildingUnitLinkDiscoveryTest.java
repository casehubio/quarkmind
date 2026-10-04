package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.Subgroup;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discovers unitLink values for Protoss buildings from tournament replays.
 * Correlates abilLink 177 CmdEvents with the selected building's unitLink
 * to determine CyberneticsCore vs TwilightCouncil disambiguation constants.
 */
@Tag("diagnostic")
class BuildingUnitLinkDiscoveryTest {

    private static final Path HSC_XXVII = Path.of(
        "../quarkmind-classifier/data/replay_packs/2025_HomeStory_Cup_XXVII");

    static boolean hscReplaysExist() {
        return Files.isDirectory(HSC_XXVII);
    }

    @Test
    @EnabledIf("hscReplaysExist")
    void discoverUnitLinksForAbilLink177() throws Exception {
        List<Path>                          replays        = findReplays(HSC_XXVII);
        Map<Integer, Integer>               unitLinkCounts = new TreeMap<>();
        Map<Integer, Map<Integer, Integer>> unitLinkByIdx  = new TreeMap<>();

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                                                     EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.gameEvents == null || replay.details == null) {continue;}

            var players = replay.details.getPlayerList();
            if (players == null || players.length < 2) {continue;}
            Set<Integer> protossUserIds = new HashSet<>();
            for (int i = 0; i < players.length; i++) {
                if (players[i].getRace() == Race.PROTOSS) {
                    protossUserIds.add(i);
                }
            }
            if (protossUserIds.isEmpty()) {continue;}

            for (int userId : protossUserIds) {
                List<Integer> currentUnitLinks = new ArrayList<>();

                for (Event raw : replay.gameEvents.getEvents()) {
                    if (raw instanceof SelectionDeltaEvent sel) {
                        if (sel.getUserId() != userId) {continue;}
                        currentUnitLinks = processSelectionDelta(sel, currentUnitLinks);
                    } else if (raw instanceof CmdEvent cmd) {
                        if (cmd.getUserId() != userId) {continue;}
                        Integer abilLink = cmd.getAbilLink();
                        if (abilLink == null || abilLink != 177) {continue;}
                        int idx = Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0);

                        if (currentUnitLinks.size() == 1) {
                            int unitLink = currentUnitLinks.get(0);
                            unitLinkCounts.merge(unitLink, 1, Integer::sum);
                            unitLinkByIdx.computeIfAbsent(unitLink, k -> new TreeMap<>())
                                         .merge(idx, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        System.out.println("\n=== unitLink Discovery for abilLink=177 (Protoss only, single-selection) ===");
        System.out.printf("Scanned %d replays%n%n", replays.size());
        for (var entry : unitLinkCounts.entrySet()) {
            System.out.printf("unitLink=%-4d  total_hits=%d%n", entry.getKey(), entry.getValue());
            Map<Integer, Integer> idxCounts = unitLinkByIdx.getOrDefault(entry.getKey(), Map.of());
            for (var ie : idxCounts.entrySet()) {
                System.out.printf("  idx=%d  count=%d%n", ie.getKey(), ie.getValue());
            }
        }

        assertThat(unitLinkCounts).as("Must find unitLinks for abilLink=177 from Protoss").isNotEmpty();
    }

    @Test
    @EnabledIf("hscReplaysExist")
    void discoverUnitLinksForAbilLink195() throws Exception {
        List<Path> replays = findReplays(HSC_XXVII);
        Map<Integer, Integer> unitLinkCounts = new TreeMap<>();
        Map<Integer, Map<Integer, Integer>> unitLinkByIdx = new TreeMap<>();

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.gameEvents == null) continue;

            for (int userId = 0; userId <= 1; userId++) {
                List<Integer> currentUnitLinks = new ArrayList<>();

                for (Event raw : replay.gameEvents.getEvents()) {
                    if (raw instanceof SelectionDeltaEvent sel) {
                        if (sel.getUserId() != userId) continue;
                        currentUnitLinks = processSelectionDelta(sel, currentUnitLinks);
                    } else if (raw instanceof CmdEvent cmd) {
                        if (cmd.getUserId() != userId) continue;
                        Integer abilLink = cmd.getAbilLink();
                        if (abilLink == null || abilLink != 195) continue;
                        int idx = Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0);

                        if (currentUnitLinks.size() == 1) {
                            int unitLink = currentUnitLinks.get(0);
                            unitLinkCounts.merge(unitLink, 1, Integer::sum);
                            unitLinkByIdx.computeIfAbsent(unitLink, k -> new TreeMap<>())
                                .merge(idx, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        System.out.println("\n=== unitLink Discovery for abilLink=195 (single-selection CmdEvents) ===");
        System.out.printf("Scanned %d replays%n%n", replays.size());
        if (unitLinkCounts.isEmpty()) {
            System.out.println("No single-selection CmdEvents found for abilLink=195");
        } else {
            for (var entry : unitLinkCounts.entrySet()) {
                System.out.printf("unitLink=%-4d  total_hits=%d%n", entry.getKey(), entry.getValue());
                Map<Integer, Integer> idxCounts = unitLinkByIdx.getOrDefault(entry.getKey(), Map.of());
                for (var ie : idxCounts.entrySet()) {
                    System.out.printf("  idx=%d  count=%d%n", ie.getKey(), ie.getValue());
                }
            }
        }
    }

    @Test
    @EnabledIf("hscReplaysExist")
    void discoverTournamentUpgradeAbilLinks() throws Exception {
        List<Path> replays = findReplays(HSC_XXVII);
        Set<Integer> knownAbilLinks = Set.of(
                42, 45, 46, 129, 147, 149, 151, 155, 159, 160, 161,
                170, 172, 173, 174, 175, 176, 214, 183, 184, 186, 193,
                73, 194, 221, 267, 309, 522, 730, 120, 249, 250, 252,
                260, 265, 268, 603, 171, 311, 196, 524, 223);

        Map<String, Map<String, Integer>> upgradeAbilFreq    = new TreeMap<>();
        Map<String, Integer>              upgradeEventCounts = new TreeMap<>();
        int                               replayCount        = 0;

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                                                     EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.gameEvents == null || replay.trackerEvents == null) {continue;}
            replayCount++;

            List<CmdRecord> commands = new ArrayList<>();
            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                    commands.add(new CmdRecord(
                            cmd.getUserId() + 1, cmd.getLoop(), cmd.getAbilLink(),
                            Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0),
                            cmd.getTargetPoint() != null));
                }
            }

            for (Event raw : replay.trackerEvents.getEvents()) {
                if (raw.getId() != hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents.ID_UPGRADE) {continue;}
                var     upgrade  = (hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent) raw;
                Integer playerId = upgrade.getPlayerId();
                if (playerId == null) {continue;}
                String upgradeName = upgrade.getUpgradeTypeName().toString();
                long   upgradeLoop = upgrade.getLoop();

                upgradeEventCounts.merge(upgradeName, 1, Integer::sum);

                CmdRecord best     = null;
                long      bestDist = Long.MAX_VALUE;
                for (CmdRecord cmd : commands) {
                    if (cmd.playerId != playerId) {continue;}
                    long dist = upgradeLoop - cmd.loop;
                    if (dist >= 500 && dist <= 5000 && dist < bestDist) {
                        if (knownAbilLinks.contains(cmd.abilLink)) {continue;}
                        bestDist = dist;
                        best     = cmd;
                    }
                }
                if (best != null) {
                    String key = best.abilLink + "/" + best.abilCmdIndex;
                    upgradeAbilFreq.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                                   .merge(key, 1, Integer::sum);
                }
            }
        }

        System.out.println("\n=== Tournament Upgrade AbilLink Discovery (HSC XXVII) ===");
        System.out.printf("Scanned %d replays%n%n", replayCount);
        for (var entry : upgradeAbilFreq.entrySet()) {
            String upgradeName = entry.getKey();
            int    totalEvents = upgradeEventCounts.getOrDefault(upgradeName, 0);
            var candidates = entry.getValue().entrySet().stream()
                                  .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                  .limit(5).toList();
            if (candidates.isEmpty()) {continue;}
            var    top   = candidates.get(0);
            double ratio = totalEvents > 0 ? (double) top.getValue() / totalEvents : 0;
            System.out.printf("  %-40s events=%d  top=%s (n=%d, ratio=%.0f%%)",
                              upgradeName, totalEvents, top.getKey(), top.getValue(), ratio * 100);
            if (ratio > 0.5) {System.out.print("  ★");}
            System.out.println();
            for (int i = 1; i < candidates.size(); i++) {
                var alt = candidates.get(i);
                System.out.printf("    alt=%s (n=%d)%n", alt.getKey(), alt.getValue());
            }
        }
        System.out.println("\n  ★ = ratio > 50% (strong candidate)");
        assertThat(upgradeAbilFreq).isNotEmpty();
    }

    @Test
    @EnabledIf("hscReplaysExist")
    void correlateUnitLinkWithUpgradeType() throws Exception {
        List<Path> replays = findReplays(HSC_XXVII);
        // upgradeName → (unitLink → count)
        Map<String, Map<Integer, Integer>> upgradeToUnitLink = new TreeMap<>();
        int                                replayCount       = 0;

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                                                     EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.gameEvents == null || replay.trackerEvents == null || replay.details == null) {
                continue;
            }
            replayCount++;

            var players = replay.details.getPlayerList();
            if (players == null || players.length < 2) {continue;}

            // For each player, track selection state and unitLinks
            Map<Integer, List<Integer>>         selectionUnitLinks = new HashMap<>();
            Map<Integer, List<CmdWithUnitLink>> cmdsByPlayer       = new HashMap<>();

            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    int           userId  = sel.getUserId();
                    List<Integer> current = selectionUnitLinks.getOrDefault(userId, new ArrayList<>());
                    current = processSelectionDelta(sel, current);
                    selectionUnitLinks.put(userId, current);
                } else if (raw instanceof CmdEvent cmd) {
                    Integer abilLink = cmd.getAbilLink();
                    if (abilLink == null) {continue;}
                    // Only track the generic research abilLinks
                    if (abilLink != 177 && abilLink != 195) {continue;}
                    int           userId    = cmd.getUserId();
                    List<Integer> unitLinks = selectionUnitLinks.getOrDefault(userId, List.of());
                    if (unitLinks.size() == 1) {
                        int idx = Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0);
                        cmdsByPlayer.computeIfAbsent(userId + 1, k -> new ArrayList<>())
                                    .add(new CmdWithUnitLink(cmd.getLoop(), abilLink, idx, unitLinks.get(0)));
                    }
                }
            }

            // Correlate with tracker UpgradeEvents
            Set<String> trackedUpgrades = new java.util.HashSet<>();
            for (var ut : io.quarkmind.domain.UpgradeType.values()) {
                trackedUpgrades.add(ut.pythonName());
            }

            for (Event raw : replay.trackerEvents.getEvents()) {
                if (raw.getId() != hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents.ID_UPGRADE) {continue;}
                var     upgrade  = (hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent) raw;
                Integer playerId = upgrade.getPlayerId();
                if (playerId == null) {continue;}
                String upgradeName = upgrade.getUpgradeTypeName().toString();
                if (!trackedUpgrades.contains(upgradeName)) {continue;}
                if (upgradeName.startsWith("Spray")) {continue;}
                long upgradeLoop = upgrade.getLoop();

                List<CmdWithUnitLink> cmds     = cmdsByPlayer.getOrDefault(playerId, List.of());
                CmdWithUnitLink       best     = null;
                long                  bestDist = Long.MAX_VALUE;
                for (CmdWithUnitLink cmd : cmds) {
                    long dist = upgradeLoop - cmd.loop;
                    if (dist >= 500 && dist <= 5000 && dist < bestDist) {
                        bestDist = dist;
                        best     = cmd;
                    }
                }
                if (best != null) {
                    upgradeToUnitLink.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                                     .merge(best.unitLink, 1, Integer::sum);
                }
            }
        }

        System.out.println("\n=== UnitLink ↔ Upgrade Correlation (abilLink 177/195 only) ===");
        System.out.printf("Scanned %d replays%n%n", replayCount);

        // Invert: unitLink → upgrades (to identify buildings)
        Map<Integer, Map<String, Integer>> unitLinkToUpgrades = new TreeMap<>();
        for (var entry : upgradeToUnitLink.entrySet()) {
            for (var ul : entry.getValue().entrySet()) {
                unitLinkToUpgrades.computeIfAbsent(ul.getKey(), k -> new TreeMap<>())
                                  .put(entry.getKey(), ul.getValue());
            }
        }

        System.out.println("--- By unitLink (building identification) ---");
        for (var entry : unitLinkToUpgrades.entrySet()) {
            System.out.printf("\nunitLink=%d:%n", entry.getKey());
            entry.getValue().entrySet().stream()
                 .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                 .forEach(e -> System.out.printf("  %-40s count=%d%n", e.getKey(), e.getValue()));
        }

        System.out.println("\n--- By upgrade (disambiguation check) ---");
        for (var entry : upgradeToUnitLink.entrySet()) {
            System.out.printf("  %-40s ", entry.getKey());
            entry.getValue().entrySet().stream()
                 .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                 .forEach(e -> System.out.printf("unitLink=%d(%d) ", e.getKey(), e.getValue()));
            System.out.println();
        }

        assertThat(upgradeToUnitLink).isNotEmpty();
    }

    @Test
    @EnabledIf("hscReplaysExist")
    void discoverBuildingUnitLinksFromTracker() throws Exception {
        List<Path> replays = findReplays(HSC_XXVII);
        Set<String> targetBuildings = Set.of(
                "CyberneticsCore", "TwilightCouncil", "Forge", "TemplarArchive",
                "RoboticsBay", "DarkShrine", "FleetBeacon", "Stargate",
                "SpawningPool", "EvolutionChamber", "HydraliskDen", "BanelingNest",
                "RoachWarren", "UltraliskCavern", "InfestationPit", "Hatchery",
                "Lair", "Hive", "Spire", "GreaterSpire",
                "EngineeringBay", "Armory", "BarracksTechLab", "FactoryTechLab",
                "StarportTechLab", "FusionCore", "GhostAcademy");
        // buildingName → (unitLink → count)
        Map<String, Map<Integer, Integer>> buildingToUnitLink = new TreeMap<>();
        int                                replayCount        = 0;

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                                                     EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.gameEvents == null || replay.trackerEvents == null) {continue;}
            replayCount++;

            // Step 1: Build tag → buildingName from tracker UnitBorn/UnitInit
            Map<String, String> tagToBuilding = new HashMap<>();
            for (Event raw : replay.trackerEvents.getEvents()) {
                int eventId = raw.getId();
                if (eventId != hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents.ID_UNIT_BORN
                    && eventId != hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents.ID_UNIT_INIT) {continue;}
                var    unitEvent = (hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent) raw;
                String unitName  = unitEvent.getUnitTypeName() != null ? unitEvent.getUnitTypeName().toString() : null;
                if (unitName == null || !targetBuildings.contains(unitName)) {continue;}
                Integer tagIndex   = unitEvent.getUnitTagIndex();
                Integer tagRecycle = unitEvent.getUnitTagRecycle();
                if (tagIndex == null || tagRecycle == null) {continue;}
                String tag = GameEventStream.decodeTag((tagIndex << 18) | tagRecycle);
                tagToBuilding.put(tag, unitName);
            }

            // Step 2: Scan selection deltas for unitLink values and match to building tags
            for (Event raw : replay.gameEvents.getEvents()) {
                if (!(raw instanceof SelectionDeltaEvent sel)) {continue;}
                var delta = sel.getDelta();
                if (delta == null) {continue;}
                var subgroups = delta.getAddSubgroups();
                var addTags   = delta.getAddUnitTags();
                if (subgroups == null || addTags == null) {continue;}

                int sgIdx       = 0;
                int sgRemaining = 0;
                int currentLink = -1;
                if (subgroups.length > 0) {
                    Integer link  = subgroups[0].getUnitLink();
                    Integer count = subgroups[0].getCount();
                    currentLink = link != null ? link : -1;
                    sgRemaining = count != null ? count : 0;
                    sgIdx       = 1;
                }
                for (Integer rawTag : addTags) {
                    if (rawTag != null && currentLink >= 0) {
                        String tag      = GameEventStream.decodeTag(rawTag);
                        String building = tagToBuilding.get(tag);
                        if (building != null) {
                            buildingToUnitLink.computeIfAbsent(building, k -> new TreeMap<>())
                                              .merge(currentLink, 1, Integer::sum);
                        }
                    }
                    sgRemaining--;
                    if (sgRemaining <= 0 && subgroups != null && sgIdx < subgroups.length) {
                        Integer link  = subgroups[sgIdx].getUnitLink();
                        Integer count = subgroups[sgIdx].getCount();
                        currentLink = link != null ? link : -1;
                        sgRemaining = count != null ? count : 0;
                        sgIdx++;
                    }
                }
            }
        }

        System.out.println("\n=== Building UnitLink Discovery (selection delta ↔ tracker) ===");
        System.out.printf("Scanned %d replays%n%n", replayCount);
        for (var entry : buildingToUnitLink.entrySet()) {
            String buildingName = entry.getKey();
            var links = entry.getValue().entrySet().stream()
                             .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                             .toList();
            int dominant      = links.get(0).getKey();
            int dominantCount = links.get(0).getValue();
            int total         = links.stream().mapToInt(Map.Entry::getValue).sum();
            System.out.printf("  %-25s unitLink=%-4d  (%d/%d = %.0f%%)",
                              buildingName, dominant, dominantCount, total, 100.0 * dominantCount / total);
            if (links.size() > 1) {
                System.out.print("  alts: ");
                for (int i = 1; i < Math.min(5, links.size()); i++) {
                    System.out.printf("%d(%d) ", links.get(i).getKey(), links.get(i).getValue());
                }
            }
            System.out.println();
        }

        // Print as Java constants
        System.out.println("\n--- Java constants ---");
        for (var entry : buildingToUnitLink.entrySet()) {
            var top = entry.getValue().entrySet().stream()
                           .max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null) {
                String constName = "UNIT_LINK_" + entry.getKey()
                                                       .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
                System.out.printf("  private static final int %-35s = %d;%n", constName, top.getKey());
            }
        }

        assertThat(buildingToUnitLink).isNotEmpty();
    }

    /**
     * For each upgrade, finds the CmdEvent that fired while the CORRECT building was selected.
     * Uses known building unitLinks to filter — only keeps CmdEvents where the selection
     * contains the building that researches the upgrade.
     */
    @Test
    @EnabledIf("hscReplaysExist")
    void discoverResearchAbilLinksWithBuildingFilter() throws Exception {
        Map<String, Integer> upgradeToBuilding0 = Map.ofEntries(
                Map.entry("WarpGateResearch", 95), Map.entry("ProtossAirWeaponsLevel1", 95),
                Map.entry("ProtossAirWeaponsLevel2", 95), Map.entry("ProtossAirWeaponsLevel3", 95),
                Map.entry("ProtossAirArmorsLevel1", 95), Map.entry("ProtossAirArmorsLevel2", 95),
                Map.entry("ProtossAirArmorsLevel3", 95),
                Map.entry("Charge", 88), Map.entry("BlinkTech", 88), Map.entry("AdeptPiercingAttack", 88),
                Map.entry("ProtossGroundWeaponsLevel1", 86), Map.entry("ProtossGroundWeaponsLevel2", 86),
                Map.entry("ProtossGroundWeaponsLevel3", 86), Map.entry("ProtossGroundArmorsLevel1", 86),
                Map.entry("ProtossGroundArmorsLevel2", 86), Map.entry("ProtossGroundArmorsLevel3", 86),
                Map.entry("ProtossShieldsLevel1", 86), Map.entry("ProtossShieldsLevel2", 86),
                Map.entry("ProtossShieldsLevel3", 86),
                Map.entry("PsiStormTech", 91), Map.entry("ExtendedThermalLance", 93),
                Map.entry("DarkTemplarBlinkUpgrade", 92), Map.entry("PhoenixRangeUpgrade", 87),
                Map.entry("zerglingmovementspeed", 112), Map.entry("zerglingattackspeed", 112),
                Map.entry("ZergMeleeWeaponsLevel1", 113), Map.entry("ZergMeleeWeaponsLevel2", 113),
                Map.entry("ZergMeleeWeaponsLevel3", 113), Map.entry("ZergGroundArmorsLevel1", 113),
                Map.entry("ZergGroundArmorsLevel2", 113), Map.entry("ZergGroundArmorsLevel3", 113),
                Map.entry("ZergMissileWeaponsLevel1", 113), Map.entry("ZergMissileWeaponsLevel2", 113),
                Map.entry("ZergMissileWeaponsLevel3", 113),
                Map.entry("EvolveGroovedSpines", 114), Map.entry("EvolveMuscularAugments", 114),
                Map.entry("CentrificalHooks", 119), Map.entry("GlialReconstitution", 120),
                Map.entry("TunnelingClaws", 120), Map.entry("ChitinousPlating", 116),
                Map.entry("AnabolicSynthesis", 116), Map.entry("InfestorEnergyUpgrade", 117),
                Map.entry("NeuralParasite", 117), Map.entry("overlordspeed", 109),
                Map.entry("Burrow", 109),
                Map.entry("ZergFlyerWeaponsLevel1", 115), Map.entry("ZergFlyerWeaponsLevel2", 115),
                Map.entry("ZergFlyerWeaponsLevel3", 115), Map.entry("ZergFlyerArmorsLevel1", 115),
                Map.entry("ZergFlyerArmorsLevel2", 115), Map.entry("ZergFlyerArmorsLevel3", 115));
        // Add Terran upgrades (EngineeringBay=43, Armory=52, TechLab=60, FusionCore=53, GhostAcademy=48)
        Map<String, Integer> upgradeToBuilding = new HashMap<>(upgradeToBuilding0);
        upgradeToBuilding.put("HiSecAutoTracking", 43); upgradeToBuilding.put("TerranBuildingArmor", 43);
        upgradeToBuilding.put("TerranInfantryWeaponsLevel1", 43); upgradeToBuilding.put("TerranInfantryWeaponsLevel2", 43);
        upgradeToBuilding.put("TerranInfantryWeaponsLevel3", 43); upgradeToBuilding.put("TerranInfantryArmorsLevel1", 43);
        upgradeToBuilding.put("TerranInfantryArmorsLevel2", 43); upgradeToBuilding.put("TerranInfantryArmorsLevel3", 43);
        upgradeToBuilding.put("TerranVehicleWeaponsLevel1", 52); upgradeToBuilding.put("TerranVehicleWeaponsLevel2", 52);
        upgradeToBuilding.put("TerranVehicleWeaponsLevel3", 52); upgradeToBuilding.put("TerranShipWeaponsLevel1", 52);
        upgradeToBuilding.put("TerranShipWeaponsLevel2", 52); upgradeToBuilding.put("TerranShipWeaponsLevel3", 52);
        upgradeToBuilding.put("TerranVehicleAndShipArmorsLevel1", 52); upgradeToBuilding.put("TerranVehicleAndShipArmorsLevel2", 52);
        upgradeToBuilding.put("TerranVehicleAndShipArmorsLevel3", 52);
        upgradeToBuilding.put("Stimpack", 60); upgradeToBuilding.put("ShieldWall", 60);
        upgradeToBuilding.put("PunisherGrenades", 60);
        upgradeToBuilding.put("BansheeCloak", 60); upgradeToBuilding.put("BansheeSpeed", 60);
        upgradeToBuilding.put("MedivacIncreaseSpeedBoost", 60); upgradeToBuilding.put("LiberatorAGRangeUpgrade", 60);
        upgradeToBuilding.put("DrillClaws", 60); upgradeToBuilding.put("SmartServos", 60);
        upgradeToBuilding.put("HighCapacityBarrels", 60); upgradeToBuilding.put("CycloneLockOnDamageUpgrade", 60);
        upgradeToBuilding.put("BattlecruiserEnableSpecializations", 53);
        upgradeToBuilding.put("PersonalCloaking", 48);

        Set<String> trackedUpgrades = new HashSet<>();
        for (var ut : io.quarkmind.domain.UpgradeType.values()) {trackedUpgrades.add(ut.pythonName());}

        List<Path> replays = findReplays(HSC_XXVII);
        // upgradeName → (abilLink/idx → count)
        Map<String, Map<String, Integer>> results       = new TreeMap<>();
        Map<String, Integer>              upgradeTotals = new TreeMap<>();
        int                               replayCount   = 0;

        for (Path rp : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(rp,
                                                     EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.gameEvents == null || replay.trackerEvents == null) {continue;}
            replayCount++;

            // Track per-userId: (loop, abilLink, idx, unitLink) for CmdEvents with single-building selection
            Map<Integer, List<long[]>>  cmdsByUser         = new HashMap<>();
            Map<Integer, List<Integer>> selectionUnitLinks = new HashMap<>();

            for (Event raw : replay.gameEvents.getEvents()) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    int           userId  = sel.getUserId();
                    List<Integer> current = selectionUnitLinks.getOrDefault(userId, new ArrayList<>());
                    current = processSelectionDelta(sel, current);
                    selectionUnitLinks.put(userId, current);
                } else if (raw instanceof CmdEvent cmd) {
                    if (cmd.getAbilLink() == null) {continue;}
                    int           userId    = cmd.getUserId();
                    List<Integer> unitLinks = selectionUnitLinks.getOrDefault(userId, List.of());
                    if (unitLinks.size() != 1) {continue;}
                    int abilLink = cmd.getAbilLink();
                    int idx      = Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0);
                    int unitLink = unitLinks.get(0);
                    cmdsByUser.computeIfAbsent(userId + 1, k -> new ArrayList<>())
                              .add(new long[]{cmd.getLoop(), abilLink, idx, unitLink});
                }
            }

            for (Event raw : replay.trackerEvents.getEvents()) {
                if (raw.getId() != hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents.ID_UPGRADE) {continue;}
                var     upgrade  = (hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent) raw;
                Integer playerId = upgrade.getPlayerId();
                if (playerId == null) {continue;}
                String upgradeName = upgrade.getUpgradeTypeName().toString();
                if (!trackedUpgrades.contains(upgradeName) || upgradeName.startsWith("Spray")) {continue;}
                Integer expectedUnitLink = upgradeToBuilding.get(upgradeName);
                if (expectedUnitLink == null) {continue;}
                long upgradeLoop = upgrade.getLoop();
                upgradeTotals.merge(upgradeName, 1, Integer::sum);

                List<long[]> cmds     = cmdsByUser.getOrDefault(playerId, List.of());
                long[]       best     = null;
                long         bestDist = Long.MAX_VALUE;
                for (long[] cmd : cmds) {
                    if (cmd[3] != expectedUnitLink) {continue;}
                    long dist = upgradeLoop - cmd[0];
                    if (dist >= 200 && dist <= 8000 && dist < bestDist) {
                        bestDist = dist;
                        best     = cmd;
                    }
                }
                if (best != null) {
                    String key = best[1] + "/" + best[2];
                    results.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                           .merge(key, 1, Integer::sum);
                }
            }
        }

        System.out.println("\n=== Research AbilLink Discovery (building-filtered) ===");
        System.out.printf("Scanned %d replays%n%n", replayCount);
        for (var entry : results.entrySet()) {
            String upgradeName = entry.getKey();
            int    total       = upgradeTotals.getOrDefault(upgradeName, 0);
            var candidates = entry.getValue().entrySet().stream()
                                  .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                  .limit(5).toList();
            if (candidates.isEmpty()) {continue;}
            var    top   = candidates.get(0);
            double ratio = total > 0 ? (double) top.getValue() / total : 0;
            System.out.printf("  %-40s events=%d  top=%s (n=%d, ratio=%.0f%%)",
                              upgradeName, total, top.getKey(), top.getValue(), ratio * 100);
            if (ratio > 0.5) {System.out.print("  ★");}
            System.out.println();
            for (int i = 1; i < candidates.size(); i++) {
                System.out.printf("    alt=%s (n=%d)%n", candidates.get(i).getKey(), candidates.get(i).getValue());
            }
        }
        System.out.println("\n  ★ = ratio > 50% (strong candidate)");
        assertThat(results).isNotEmpty();
    }


    private record CmdWithUnitLink(long loop, int abilLink, int idx, int unitLink) {}


    private record CmdRecord(int playerId, long loop, int abilLink, int abilCmdIndex, boolean hasTargetPoint) {}


    private List<Path> findReplays(Path dir) throws IOException {
        List<Path> replays = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted()
                .forEach(replays::add);
        }
        return replays;
    }

    private List<Integer> processSelectionDelta(SelectionDeltaEvent sel, List<Integer> current) {
        var delta = sel.getDelta();
        if (delta == null) return new ArrayList<>();

        var removeMask = delta.getRemoveMask();
        String variant = removeMask != null ? removeMask.value1 : null;

        if ("ZeroIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
            List<Integer> kept = new ArrayList<>();
            for (int idx : indices) {
                if (idx >= 0 && idx < current.size()) {
                    kept.add(current.get(idx));
                }
            }
            current = kept;
        } else if ("OneIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
            current = new ArrayList<>(current);
            for (int i = indices.length - 1; i >= 0; i--) {
                int idx = indices[i];
                if (idx >= 0 && idx < current.size()) {
                    current.remove(idx);
                }
            }
        } else if (variant != null && !"None".equals(variant)) {
            current = new ArrayList<>();
        }

        var addSubgroups = delta.getAddSubgroups();
        if (addSubgroups != null) {
            for (Subgroup sg : addSubgroups) {
                Integer unitLink = sg.getUnitLink();
                Integer count = sg.getCount();
                if (unitLink != null && count != null) {
                    for (int i = 0; i < count; i++) {
                        current.add(unitLink);
                    }
                }
            }
        }
        return current;
    }
}
