package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import io.quarkmind.domain.UpgradeType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

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

/**
 * Brute-force offset calibration: for each dataset, tries abilLinkOffset -10 to +10
 * and reports upgrade detection accuracy. Bypasses AbilityProfile enum by doing direct
 * abilLink matching against known V4_9_3 upgrade constants.
 *
 * Answers: "can IEM 2018 / ASUS ROG 2020 be handled by a simple abilLink offset,
 * or do they need per-upgrade overrides?"
 */
@Tag("diagnostic")
class AbilLinkOffsetCalibrationTest {

    private static final Path REPLAY_PACKS = Path.of("../quarkmind-classifier/data/replay_packs");

    private static final Map<Integer, Map<Integer, String>> UPGRADE_ABIL_LINKS = buildUpgradeMap();

    private static Map<Integer, Map<Integer, String>> buildUpgradeMap() {
        var m = new HashMap<Integer, Map<Integer, String>>();
        m.put(162, Map.of(0, "HiSecAutoTracking", 1, "TerranBuildingArmor",
            2, "TerranInfantryWeaponsLevel1", 3, "TerranInfantryWeaponsLevel2",
            4, "TerranInfantryWeaponsLevel3",
            6, "TerranInfantryArmorsLevel1", 7, "TerranInfantryArmorsLevel2",
            8, "TerranInfantryArmorsLevel3"));
        m.put(165, Map.of(0, "Stimpack", 1, "ShieldWall", 2, "PunisherGrenades"));
        m.put(166, Map.of(1, "HighCapacityBarrels", 4, "DrillClaws", 6, "SmartServos"));
        m.put(167, Map.of(0, "BansheeCloak", 3, "RavenCorvidReactor",
            9, "BansheeSpeed", 14, "MedivacIncreaseSpeedBoost", 15, "LiberatorAGRangeUpgrade"));
        m.put(169, Map.ofEntries(
            Map.entry(5, "TerranVehicleWeaponsLevel1"), Map.entry(6, "TerranVehicleWeaponsLevel2"),
            Map.entry(7, "TerranVehicleWeaponsLevel3"),
            Map.entry(11, "TerranShipWeaponsLevel1"), Map.entry(12, "TerranShipWeaponsLevel2"),
            Map.entry(13, "TerranShipWeaponsLevel3"),
            Map.entry(14, "TerranVehicleAndShipArmorsLevel1"), Map.entry(15, "TerranVehicleAndShipArmorsLevel2"),
            Map.entry(16, "TerranVehicleAndShipArmorsLevel3")));
        m.put(235, Map.of(0, "BattlecruiserEnableSpecializations"));
        m.put(224, Map.of(0, "CentrificalHooks"));
        m.put(107, Map.of(1, "GlialReconstitution", 2, "TunnelingClaws"));
        m.put(185, Map.ofEntries(
            Map.entry(0, "ZergMeleeWeaponsLevel1"), Map.entry(1, "ZergMeleeWeaponsLevel2"),
            Map.entry(2, "ZergMeleeWeaponsLevel3"), Map.entry(3, "ZergGroundArmorsLevel1"),
            Map.entry(4, "ZergGroundArmorsLevel2"), Map.entry(5, "ZergGroundArmorsLevel3"),
            Map.entry(6, "ZergMissileWeaponsLevel1"), Map.entry(7, "ZergMissileWeaponsLevel2"),
            Map.entry(8, "ZergMissileWeaponsLevel3")));
        m.put(192, Map.of(0, "ZergFlyerWeaponsLevel1", 1, "ZergFlyerWeaponsLevel2",
            2, "ZergFlyerWeaponsLevel3", 3, "ZergFlyerArmorsLevel1",
            4, "ZergFlyerArmorsLevel2", 5, "ZergFlyerArmorsLevel3"));
        m.put(262, Map.of(0, "EvolveGroovedSpines"));
        m.put(310, Map.of(0, "EvolveMuscularAugments"));
        m.put(191, Map.of(0, "EvolveGroovedSpines", 1, "EvolveMuscularAugments"));
        m.put(117, Map.of(0, "AnabolicSynthesis", 2, "ChitinousPlating"));
        m.put(190, Map.of(0, "zerglingattackspeed", 1, "zerglingmovementspeed"));
        m.put(189, Map.of(1, "overlordspeed", 3, "Burrow"));
        m.put(223, Map.of(2, "InfestorEnergyUpgrade", 3, "NeuralParasite"));
        m.put(180, Map.ofEntries(
            Map.entry(0, "ProtossGroundWeaponsLevel1"), Map.entry(1, "ProtossGroundWeaponsLevel2"),
            Map.entry(2, "ProtossGroundWeaponsLevel3"), Map.entry(3, "ProtossGroundArmorsLevel1"),
            Map.entry(4, "ProtossGroundArmorsLevel2"), Map.entry(5, "ProtossGroundArmorsLevel3"),
            Map.entry(6, "ProtossShieldsLevel1"), Map.entry(7, "ProtossShieldsLevel2"),
            Map.entry(8, "ProtossShieldsLevel3")));
        m.put(236, Map.of(0, "ProtossAirWeaponsLevel1", 1, "ProtossAirWeaponsLevel2",
            2, "ProtossAirWeaponsLevel3", 3, "ProtossAirArmorsLevel1",
            4, "ProtossAirArmorsLevel2", 5, "ProtossAirArmorsLevel3",
            6, "WarpGateResearch"));
        m.put(237, Map.of(0, "Charge", 1, "BlinkTech", 2, "AdeptPiercingAttack"));
        m.put(182, Map.of(4, "PsiStormTech"));
        m.put(181, Map.of(1, "GraviticDrive", 5, "ExtendedThermalLance", 7, "ObserverGraviticBooster"));
        m.put(177, Map.of(0, "DarkTemplarBlinkUpgrade"));
        m.put(71, Map.of(2, "PhoenixRangeUpgrade", 3, "TempestGroundAttackUpgrade"));
        m.put(608, Map.of(0, "DarkTemplarBlinkUpgrade"));
        m.put(69, Map.of(2, "PhoenixRangeUpgrade"));
        m.put(148, Map.of(0, "CycloneLockOnDamageUpgrade"));
        return m;
    }

    record DatasetSource(String name, Path dir) {}

    private static List<DatasetSource> allDatasets() {
        var ds = new ArrayList<DatasetSource>();
        ds.add(new DatasetSource("Oracle (4.9.3)", REPLAY_PACKS.resolve("blizzard_ladder/4.9.3_oracle/restored")));
        ds.add(new DatasetSource("AI Arena (4.9.3)", Path.of("replays/aiarena_protoss")));
        ds.add(new DatasetSource("IEM 2018", REPLAY_PACKS.resolve("2018_IEM_PyeongChang")));
        ds.add(new DatasetSource("ASUS ROG 2020", REPLAY_PACKS.resolve("2020_ASUS_ROG_Online")));
        return ds;
    }

    static boolean anyDatasetExists() {
        return allDatasets().stream().anyMatch(ds -> Files.isDirectory(ds.dir()));
    }

    @Test
    @EnabledIf("anyDatasetExists")
    void calibrateOffsetsPerDataset() throws Exception {
        Set<String> trackedUpgrades = new HashSet<>();
        for (var ut : UpgradeType.values()) {
            trackedUpgrades.add(ut.pythonName());
        }

        System.out.printf("%n=== AbilLink Offset Calibration (brute force -10..+10) ===%n%n");

        for (var ds : allDatasets()) {
            if (!Files.isDirectory(ds.dir())) {
                System.out.printf("SKIP %s (not found)%n%n", ds.name());
                continue;
            }

            List<ReplayData> replays = loadReplays(ds.dir());
            if (replays.isEmpty()) {
                System.out.printf("SKIP %s (no valid replays)%n%n", ds.name());
                continue;
            }

            int baseBuild = replays.get(0).baseBuild;
            System.out.printf("%-30s baseBuild=%d  replays=%d%n", ds.name(), baseBuild, replays.size());

            int bestOffset = 0;
            double bestAccuracy = 0;
            Map<Integer, int[]> offsetResults = new TreeMap<>();

            for (int offset = -10; offset <= 10; offset++) {
                int totalOracle = 0, totalDetected = 0;

                for (ReplayData rd : replays) {
                    Set<String> detected = new HashSet<>();
                    for (CmdRecord cmd : rd.commands) {
                        int normalized = cmd.abilLink - offset;
                        var upgradeMap = UPGRADE_ABIL_LINKS.get(normalized);
                        if (upgradeMap != null) {
                            String upgrade = upgradeMap.get(cmd.abilCmdIndex);
                            if (upgrade != null) detected.add(cmd.playerId + ":" + upgrade);
                        }
                    }
                    for (UpgradeEvent ue : rd.upgrades) {
                        if (!trackedUpgrades.contains(ue.name)) continue;
                        totalOracle++;
                        if (detected.contains(ue.playerId + ":" + ue.name)) totalDetected++;
                    }
                }

                double accuracy = totalOracle > 0 ? 100.0 * totalDetected / totalOracle : 0;
                offsetResults.put(offset, new int[]{totalDetected, totalOracle});
                if (accuracy > bestAccuracy) { bestAccuracy = accuracy; bestOffset = offset; }
            }

            final int fb = bestOffset;
            offsetResults.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
                .limit(5)
                .forEach(e -> {
                    int[] v = e.getValue();
                    double acc = v[1] > 0 ? 100.0 * v[0] / v[1] : 0;
                    System.out.printf("  offset=%+3d  %d/%d (%.1f%%)%s%n",
                        e.getKey(), v[0], v[1], acc, e.getKey() == fb ? "  ★" : "");
                });

            System.out.printf("  → Best offset: %+d (%.1f%%)%n", bestOffset, bestAccuracy);

            if (bestAccuracy < 99.0) {
                Map<String, int[]> perUpgrade = new TreeMap<>();
                for (ReplayData rd : replays) {
                    Set<String> detected = new HashSet<>();
                    for (CmdRecord cmd : rd.commands) {
                        int normalized = cmd.abilLink - fb;
                        var upgradeMap = UPGRADE_ABIL_LINKS.get(normalized);
                        if (upgradeMap != null) {
                            String upgrade = upgradeMap.get(cmd.abilCmdIndex);
                            if (upgrade != null) detected.add(cmd.playerId + ":" + upgrade);
                        }
                    }
                    for (UpgradeEvent ue : rd.upgrades) {
                        if (!trackedUpgrades.contains(ue.name)) continue;
                        int[] counts = perUpgrade.computeIfAbsent(ue.name, k -> new int[2]);
                        counts[0]++;
                        if (detected.contains(ue.playerId + ":" + ue.name)) counts[1]++;
                    }
                }
                System.out.println("  Missed at best offset:");
                perUpgrade.entrySet().stream()
                    .filter(e -> e.getValue()[1] < e.getValue()[0])
                    .sorted((a, b) -> (b.getValue()[0] - b.getValue()[1]) - (a.getValue()[0] - a.getValue()[1]))
                    .forEach(e -> System.out.printf("    %-40s %d/%d%n",
                        e.getKey(), e.getValue()[1], e.getValue()[0]));
            }
            System.out.println();
        }
    }

    private List<ReplayData> loadReplays(Path dir) throws Exception {
        List<ReplayData> result = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path rp : s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList()) {
                Replay rep;
                try {
                    rep = RepParserEngine.parseReplay(rp,
                        EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
                } catch (Exception e) { continue; }
                if (rep == null || rep.trackerEvents == null || rep.gameEvents == null) continue;

                int baseBuild = rep.header != null && rep.header.baseBuild != null ? rep.header.baseBuild : 0;

                List<CmdRecord> commands = new ArrayList<>();
                for (var raw : rep.gameEvents.getEvents()) {
                    if (raw instanceof CmdEvent cmd && cmd.getAbilLink() != null) {
                        if (cmd.getTargetPoint() != null) continue;
                        commands.add(new CmdRecord(cmd.getUserId() + 1, cmd.getLoop(),
                            cmd.getAbilLink(), Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0)));
                    }
                }

                List<UpgradeEvent> upgrades = new ArrayList<>();
                for (Event raw : rep.trackerEvents.getEvents()) {
                    if (raw.getId() != ITrackerEvents.ID_UPGRADE) continue;
                    IUpgradeEvent ue = (IUpgradeEvent) raw;
                    if (ue.getPlayerId() == null) continue;
                    upgrades.add(new UpgradeEvent(ue.getPlayerId(), ue.getUpgradeTypeName().toString()));
                }

                result.add(new ReplayData(baseBuild, commands, upgrades));
            }
        }
        return result;
    }

    record ReplayData(int baseBuild, List<CmdRecord> commands, List<UpgradeEvent> upgrades) {}
    record CmdRecord(int playerId, long loop, int abilLink, int abilCmdIndex) {}
    record UpgradeEvent(int playerId, String name) {}
}
