package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UpgradeType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

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
 * Discovers abilLink mappings for intermediate SC2 patches by cross-referencing
 * game event commands with tracker events. Runs against IEM 2018 (baseBuild=60321)
 * and ASUS ROG 2020 (baseBuild=82457) datasets.
 *
 * For each upgrade tracker event at loop T, finds all CmdEvents from the same player
 * within [researchTime - tolerance, researchTime + tolerance] loops before the upgrade
 * completion. Groups by abilLink/abilCmdIndex, reporting the most consistent matches.
 *
 * Also discovers unit train and building placement abilLinks using the same correlation
 * approach against UnitBorn and UnitInit tracker events.
 */
@Tag("diagnostic")
class CrossPatchAbilLinkDiscoveryTest {

    private static final Path REPLAY_PACKS = Path.of("../quarkmind-classifier/data/replay_packs");
    private static final Path IEM_2018 = REPLAY_PACKS.resolve("2018_IEM_PyeongChang");
    private static final Path ASUS_ROG_2020 = REPLAY_PACKS.resolve("2020_ASUS_ROG_Online");

    // abilLinks known from V4_9_3 — to identify the offset
    private static final Map<String, Integer> V4_9_3_UPGRADE_ABIL_LINKS = Map.ofEntries(
        Map.entry("EngineeringBay", 162), Map.entry("Stimpack", 165),
        Map.entry("StarportTechLab", 167), Map.entry("FactoryTechLab", 166),
        Map.entry("Armory", 169), Map.entry("FusionCore", 235),
        Map.entry("BanelingNest", 224), Map.entry("RoachWarren", 107),
        Map.entry("EvolutionChamber", 185), Map.entry("Spire", 192),
        Map.entry("HydraliskDen", 262), Map.entry("UltraliskCavern", 117),
        Map.entry("SpawningPool", 190), Map.entry("HatcheryUpgrade", 189),
        Map.entry("InfestationPit", 223), Map.entry("Forge", 180),
        Map.entry("CyberneticsCore", 236), Map.entry("TwilightResearch", 237),
        Map.entry("TemplarArchive", 182), Map.entry("RoboticsBay", 181),
        Map.entry("DarkShrine", 177), Map.entry("FleetBeacon", 71)
    );

    // Train abilLinks from V4_9_3
    private static final Map<String, Integer> V4_9_3_TRAIN_ABIL_LINKS = Map.ofEntries(
        Map.entry("Gateway", 172), Map.entry("Stargate", 173),
        Map.entry("Robotics", 174), Map.entry("Nexus", 175),
        Map.entry("Larva", 193), Map.entry("Hatchery", 184),
        Map.entry("Lair", 186), Map.entry("CommandCenter", 155),
        Map.entry("Barracks", 159), Map.entry("Factory", 160),
        Map.entry("Starport", 161), Map.entry("WarpGate", 170)
    );

    static boolean iem2018Exists() { return Files.isDirectory(IEM_2018); }
    static boolean asusRog2020Exists() { return Files.isDirectory(ASUS_ROG_2020); }

    @Test
    @EnabledIf("iem2018Exists")
    void discoverIem2018UpgradeAbilLinks() throws Exception {
        System.out.println("\n=== IEM PyeongChang 2018 (baseBuild=60321) Upgrade AbilLink Discovery ===\n");
        discoverUpgradeAbilLinks(IEM_2018, "IEM 2018");
    }

    @Test
    @EnabledIf("asusRog2020Exists")
    void discoverAsusRog2020UpgradeAbilLinks() throws Exception {
        System.out.println("\n=== ASUS ROG 2020 (baseBuild=82457) Upgrade AbilLink Discovery ===\n");
        discoverUpgradeAbilLinks(ASUS_ROG_2020, "ASUS ROG 2020");
    }

    @Test
    @EnabledIf("iem2018Exists")
    void discoverIem2018TrainAbilLinks() throws Exception {
        System.out.println("\n=== IEM 2018 Train AbilLink Discovery ===\n");
        discoverTrainAbilLinks(IEM_2018, "IEM 2018");
    }

    @Test
    @EnabledIf("asusRog2020Exists")
    void discoverAsusRog2020TrainAbilLinks() throws Exception {
        System.out.println("\n=== ASUS ROG 2020 Train AbilLink Discovery ===\n");
        discoverTrainAbilLinks(ASUS_ROG_2020, "ASUS ROG 2020");
    }

    @Test
    @EnabledIf("iem2018Exists")
    void computeIem2018AbilLinkOffset() throws Exception {
        System.out.println("\n=== IEM 2018 AbilLink Offset Analysis ===\n");
        computeOffsetFromTrainAbilLinks(IEM_2018);
    }

    @Test
    @EnabledIf("asusRog2020Exists")
    void computeAsusRog2020AbilLinkOffset() throws Exception {
        System.out.println("\n=== ASUS ROG 2020 AbilLink Offset Analysis ===\n");
        computeOffsetFromTrainAbilLinks(ASUS_ROG_2020);
    }

    private void discoverUpgradeAbilLinks(Path datasetDir, String label) throws Exception {
        Map<String, Integer> researchTimes = new HashMap<>();
        for (var ut : UpgradeType.values()) {
            researchTimes.put(ut.pythonName(), SC2Data.upgradeTimeInLoops(ut));
        }

        // upgrade → (abilLink/idx → list of distances)
        Map<String, Map<String, List<Integer>>> correlations = new TreeMap<>();
        Map<String, Integer> eventCounts = new TreeMap<>();
        int replayCount = 0;

        try (var stream = Files.list(datasetDir)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay rep;
                try {
                    rep = RepParserEngine.parseReplay(rp,
                        EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
                } catch (Exception e) { continue; }
                if (rep == null || rep.trackerEvents == null || rep.gameEvents == null) continue;
                replayCount++;

                List<CmdRecord> commands = extractCommands(rep);
                if (commands.isEmpty()) continue;

                for (Event raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                    IUpgradeEvent upgrade = (IUpgradeEvent) raw;
                    if (upgrade.getPlayerId() == null) continue;
                    String upgradeName = upgrade.getUpgradeTypeName().toString();
                    Integer researchTime = researchTimes.get(upgradeName);
                    if (researchTime == null) continue;

                    eventCounts.merge(upgradeName, 1, Integer::sum);
                    long upgradeLoop = upgrade.getLoop();

                    for (CmdRecord cmd : commands) {
                        if (cmd.playerId != upgrade.getPlayerId()) continue;
                        if (cmd.hasTargetPoint) continue;
                        long dist = upgradeLoop - cmd.loop;
                        if (dist < 200 || dist > 5000) continue;
                        String key = cmd.abilLink + "/" + cmd.abilCmdIndex;
                        correlations.computeIfAbsent(upgradeName, k -> new TreeMap<>())
                            .computeIfAbsent(key, k -> new ArrayList<>())
                            .add((int) dist);
                    }
                }
            }
        }

        System.out.printf("  %s: %d replays processed%n%n", label, replayCount);

        // Print results — for each upgrade, the top candidate with lowest CV
        System.out.printf("  %-35s %-12s %4s %8s %6s  %-12s %s%n",
            "Upgrade", "abilLink/idx", "n", "dist", "cv", "V4_9_3 ref", "offset");
        System.out.println("  " + "-".repeat(100));

        for (var entry : correlations.entrySet()) {
            String upgradeName = entry.getKey();
            int events = eventCounts.getOrDefault(upgradeName, 0);
            var candidates = entry.getValue().entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .sorted((a, b) -> Double.compare(cv(a.getValue()), cv(b.getValue())))
                .limit(3)
                .toList();
            if (candidates.isEmpty()) continue;

            var best = candidates.get(0);
            double bestCv = cv(best.getValue());
            int modal = modal(best.getValue());
            String bestKey = best.getKey();
            int bestAbilLink = Integer.parseInt(bestKey.split("/")[0]);

            // Find V4_9_3 reference for offset calculation
            String v493Ref = "—";
            String offsetStr = "—";
            Integer researchTime = researchTimes.get(upgradeName);

            // Match against known V4_9_3 upgrade abilLinks by upgrade name proximity
            for (var ref : V4_9_3_UPGRADE_ABIL_LINKS.entrySet()) {
                // Check if this upgrade matches the building name
                if (upgradeName.toLowerCase().contains(ref.getKey().toLowerCase().replace("upgrade", "").replace("techlab", "").trim())
                    || matchesByBuilding(upgradeName, ref.getKey())) {
                    v493Ref = ref.getKey() + "=" + ref.getValue();
                    offsetStr = String.valueOf(bestAbilLink - ref.getValue());
                    break;
                }
            }

            System.out.printf("  %-35s %-12s %4d %8d %6.2f  %-12s %s%s%n",
                upgradeName + " (" + events + ")",
                bestKey, best.getValue().size(), modal, bestCv,
                v493Ref, offsetStr, bestCv < 0.15 ? "  ★" : "");
        }
    }

    private void discoverTrainAbilLinks(Path datasetDir, String label) throws Exception {
        // unitName → (abilLink/idx → count)
        Map<String, Map<String, Integer>> mappings = new TreeMap<>();
        int replayCount = 0;

        try (var stream = Files.list(datasetDir)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay rep;
                try {
                    rep = RepParserEngine.parseReplay(rp,
                        EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
                } catch (Exception e) { continue; }
                if (rep == null || rep.trackerEvents == null || rep.gameEvents == null) continue;
                replayCount++;

                List<CmdRecord> commands = extractCommands(rep);
                if (commands.isEmpty()) continue;

                for (Event raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UNIT_BORN) continue;
                    IBaseUnitEvent ub = (IBaseUnitEvent) raw;
                    Integer ctrlId = ub.getControlPlayerId();
                    if (ctrlId == null || ctrlId == 0 || ub.getLoop() == 0) continue;

                    String unitName = ub.getUnitTypeName().toString();
                    // Skip structures and non-trainable units
                    if (isStructure(unitName) || isAutoSpawn(unitName)) continue;

                    CmdRecord best = null;
                    long bestDist = Long.MAX_VALUE;
                    for (CmdRecord cmd : commands) {
                        if (cmd.playerId != ctrlId) continue;
                        long dist = ub.getLoop() - cmd.loop;
                        if (dist >= 100 && dist <= 2000 && dist < bestDist) {
                            bestDist = dist;
                            best = cmd;
                        }
                    }
                    if (best != null) {
                        String key = best.abilLink + "/" + best.abilCmdIndex;
                        mappings.computeIfAbsent(unitName, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.printf("  %s: %d replays%n%n", label, replayCount);
        System.out.printf("  %-25s %-12s %5s  %-15s %s%n", "Unit", "abilLink/idx", "n", "V4_9_3 ref", "offset");
        System.out.println("  " + "-".repeat(80));

        for (var entry : mappings.entrySet()) {
            var sorted = entry.getValue().entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .toList();
            if (sorted.isEmpty()) continue;
            var best = sorted.get(0);
            int bestAbilLink = Integer.parseInt(best.getKey().split("/")[0]);

            String v493Ref = "—";
            String offsetStr = "—";
            for (var ref : V4_9_3_TRAIN_ABIL_LINKS.entrySet()) {
                if (trainMatchesBuilding(entry.getKey(), ref.getKey())) {
                    v493Ref = ref.getKey() + "=" + ref.getValue();
                    offsetStr = String.valueOf(bestAbilLink - ref.getValue());
                    break;
                }
            }

            System.out.printf("  %-25s %-12s %5d  %-15s %s%n",
                entry.getKey(), best.getKey(), best.getValue(), v493Ref, offsetStr);
        }
    }

    private void computeOffsetFromTrainAbilLinks(Path datasetDir) throws Exception {
        // Discover actual train abilLinks and compare to V4_9_3
        Map<String, Integer> discovered = new TreeMap<>();
        Map<String, Map<String, Integer>> unitAbilLinks = new TreeMap<>();

        try (var stream = Files.list(datasetDir)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay rep;
                try {
                    rep = RepParserEngine.parseReplay(rp,
                        EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
                } catch (Exception e) { continue; }
                if (rep == null || rep.trackerEvents == null || rep.gameEvents == null) continue;

                List<CmdRecord> commands = extractCommands(rep);

                // Look at high-confidence units: Probe (Nexus train), SCV (CC train), Marine (Barracks)
                for (Event raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UNIT_BORN) continue;
                    IBaseUnitEvent ub = (IBaseUnitEvent) raw;
                    Integer ctrlId = ub.getControlPlayerId();
                    if (ctrlId == null || ctrlId == 0 || ub.getLoop() == 0) continue;

                    String unitName = ub.getUnitTypeName().toString();
                    if (!Set.of("Probe", "SCV", "Marine", "Zealot", "Stalker", "Drone", "Zergling").contains(unitName))
                        continue;

                    CmdRecord best = null;
                    long bestDist = Long.MAX_VALUE;
                    for (CmdRecord cmd : commands) {
                        if (cmd.playerId != ctrlId) continue;
                        long dist = ub.getLoop() - cmd.loop;
                        if (dist >= 100 && dist <= 1500 && dist < bestDist) {
                            bestDist = dist;
                            best = cmd;
                        }
                    }
                    if (best != null) {
                        String key = best.abilLink + "/" + best.abilCmdIndex;
                        unitAbilLinks.computeIfAbsent(unitName, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        // Extract modal abilLink per unit
        Map<String, Integer> modalAbilLinks = new TreeMap<>();
        for (var entry : unitAbilLinks.entrySet()) {
            var best = entry.getValue().entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
            if (best != null) {
                modalAbilLinks.put(entry.getKey(), Integer.parseInt(best.getKey().split("/")[0]));
            }
        }

        // Compare against V4_9_3 reference
        Map<String, Integer> referenceUnits = Map.of(
            "Probe", 175, "SCV", 155, "Marine", 159,
            "Zealot", 172, "Stalker", 172, "Drone", 193, "Zergling", 193
        );

        System.out.printf("  %-15s  %-10s  %-10s  %s%n", "Unit", "V4_9_3", "Discovered", "Offset");
        System.out.println("  " + "-".repeat(55));

        List<Integer> offsets = new ArrayList<>();
        for (var entry : modalAbilLinks.entrySet()) {
            Integer ref = referenceUnits.get(entry.getKey());
            if (ref != null) {
                int offset = entry.getValue() - ref;
                offsets.add(offset);
                System.out.printf("  %-15s  %-10d  %-10d  %+d%n",
                    entry.getKey(), ref, entry.getValue(), offset);
            }
        }

        if (!offsets.isEmpty()) {
            int modalOffset = modal(offsets);
            long consistency = offsets.stream().filter(o -> o == modalOffset).count();
            System.out.printf("%n  Modal offset: %+d (%d/%d units consistent)%n",
                modalOffset, consistency, offsets.size());
        }
    }

    private List<CmdRecord> extractCommands(Replay rep) {
        List<CmdRecord> commands = new ArrayList<>();
        for (var raw : rep.gameEvents.getEvents()) {
            if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                commands.add(new CmdRecord(
                    cmd.getUserId() + 1, cmd.getLoop(), cmd.getAbilLink(),
                    Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0),
                    cmd.getTargetPoint() != null));
            }
        }
        return commands;
    }

    private static boolean isStructure(String name) {
        return Set.of("Nexus", "Pylon", "Gateway", "Forge", "CyberneticsCore",
            "TwilightCouncil", "TemplarArchive", "DarkShrine", "Stargate",
            "RoboticsFacility", "RoboticsBay", "FleetBeacon", "PhotonCannon",
            "ShieldBattery", "Assimilator",
            "CommandCenter", "SupplyDepot", "Barracks", "Factory", "Starport",
            "EngineeringBay", "Armory", "FusionCore", "GhostAcademy",
            "MissileTurret", "SensorTower", "Bunker", "Refinery",
            "Hatchery", "SpawningPool", "EvolutionChamber", "Extractor",
            "RoachWarren", "BanelingNest", "HydraliskDen", "Spire",
            "InfestationPit", "UltraliskCavern", "NydusNetwork",
            "SpineCrawler", "SporeCrawler", "LurkerDenMP",
            "BarracksTechLab", "BarracksReactor", "FactoryTechLab",
            "FactoryReactor", "StarportTechLab", "StarportReactor",
            "OrbitalCommand", "PlanetaryFortress", "Lair", "Hive",
            "GreaterSpire", "CreepTumor", "CreepTumorQueen",
            "SupplyDepotLowered", "NydusCanal").contains(name);
    }

    private static boolean isAutoSpawn(String name) {
        return Set.of("Larva", "Interceptor", "AutoTurret", "Locust",
            "Broodling", "InfestedTerran", "Changeling", "MULE",
            "ChangelingMarine", "ChangelingMarineShield", "ChangelingZergling",
            "ChangelingZealot").contains(name);
    }

    private static boolean matchesByBuilding(String upgradeName, String buildingKey) {
        return switch (buildingKey) {
            case "EngineeringBay" -> upgradeName.startsWith("TerranInfantry") || upgradeName.equals("HiSecAutoTracking") || upgradeName.equals("TerranBuildingArmor");
            case "Stimpack" -> upgradeName.equals("Stimpack") || upgradeName.equals("ShieldWall") || upgradeName.equals("PunisherGrenades");
            case "StarportTechLab" -> upgradeName.equals("BansheeCloak") || upgradeName.equals("BansheeSpeed") || upgradeName.equals("RavenCorvidReactor") || upgradeName.equals("MedivacIncreaseSpeedBoost") || upgradeName.equals("LiberatorAGRangeUpgrade");
            case "FactoryTechLab" -> upgradeName.equals("DrillClaws") || upgradeName.equals("HighCapacityBarrels") || upgradeName.equals("SmartServos");
            case "Armory" -> upgradeName.startsWith("TerranVehicle") || upgradeName.startsWith("TerranShip");
            case "FusionCore" -> upgradeName.equals("BattlecruiserEnableSpecializations");
            case "BanelingNest" -> upgradeName.equals("CentrificalHooks");
            case "RoachWarren" -> upgradeName.equals("GlialReconstitution") || upgradeName.equals("TunnelingClaws");
            case "EvolutionChamber" -> upgradeName.startsWith("ZergMelee") || upgradeName.startsWith("ZergGroundArmor") || upgradeName.startsWith("ZergMissile");
            case "Spire" -> upgradeName.startsWith("ZergFlyer");
            case "HydraliskDen" -> upgradeName.equals("EvolveGroovedSpines") || upgradeName.equals("EvolveMuscularAugments");
            case "UltraliskCavern" -> upgradeName.equals("AnabolicSynthesis") || upgradeName.equals("ChitinousPlating");
            case "SpawningPool" -> upgradeName.equals("zerglingattackspeed") || upgradeName.equals("zerglingmovementspeed");
            case "HatcheryUpgrade" -> upgradeName.equals("overlordspeed") || upgradeName.equals("Burrow");
            case "InfestationPit" -> upgradeName.equals("InfestorEnergyUpgrade") || upgradeName.equals("NeuralParasite");
            case "Forge" -> upgradeName.startsWith("ProtossGround") || upgradeName.startsWith("ProtossShields");
            case "CyberneticsCore" -> upgradeName.startsWith("ProtossAir") || upgradeName.equals("WarpGateResearch");
            case "TwilightResearch" -> upgradeName.equals("Charge") || upgradeName.equals("BlinkTech") || upgradeName.equals("AdeptPiercingAttack");
            case "TemplarArchive" -> upgradeName.equals("PsiStormTech");
            case "RoboticsBay" -> upgradeName.equals("ExtendedThermalLance") || upgradeName.equals("GraviticDrive") || upgradeName.equals("ObserverGraviticBooster");
            case "DarkShrine" -> upgradeName.equals("DarkTemplarBlinkUpgrade");
            case "FleetBeacon" -> upgradeName.equals("PhoenixRangeUpgrade") || upgradeName.equals("TempestGroundAttackUpgrade");
            default -> false;
        };
    }

    private static boolean trainMatchesBuilding(String unitName, String buildingKey) {
        return switch (buildingKey) {
            case "Gateway" -> Set.of("Zealot", "Stalker", "Sentry", "Adept", "DarkTemplar", "HighTemplar").contains(unitName);
            case "Stargate" -> Set.of("Phoenix", "Oracle", "VoidRay", "Carrier", "Tempest").contains(unitName);
            case "Robotics" -> Set.of("Observer", "Immortal", "Colossus", "WarpPrism", "Disruptor").contains(unitName);
            case "Nexus" -> "Probe".equals(unitName);
            case "Larva" -> Set.of("Drone", "Zergling", "Overlord", "Hydralisk", "Mutalisk", "Ultralisk", "Roach", "Infestor", "Corruptor", "Viper", "SwarmHost").contains(unitName);
            case "Hatchery", "Lair" -> "Queen".equals(unitName);
            case "CommandCenter" -> "SCV".equals(unitName);
            case "Barracks" -> Set.of("Marine", "Marauder", "Reaper", "Ghost").contains(unitName);
            case "Factory" -> Set.of("Hellion", "Hellbat", "SiegeTank", "Cyclone", "Thor", "WidowMine").contains(unitName);
            case "Starport" -> Set.of("Medivac", "Viking", "Banshee", "Raven", "Battlecruiser", "Liberator").contains(unitName);
            case "WarpGate" -> false; // WarpGate uses different mechanism
            default -> false;
        };
    }

    private static double cv(List<Integer> values) {
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (mean == 0) return 999;
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        return Math.sqrt(variance) / mean;
    }

    private static int modal(List<Integer> values) {
        Map<Integer, Integer> freq = new TreeMap<>();
        for (int v : values) freq.merge(v, 1, Integer::sum);
        return freq.entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(0);
    }

    record CmdRecord(int playerId, long loop, int abilLink, int abilCmdIndex, boolean hasTargetPoint) {}
}
