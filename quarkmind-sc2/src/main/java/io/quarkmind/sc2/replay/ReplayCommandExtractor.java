package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UpgradeType;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.MorphIntent;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TimedIntent;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public final class ReplayCommandExtractor {

    private ReplayCommandExtractor() {}

    public static ReplayCommandStream extract(Path replayPath, int playerId) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.GAME_EVENTS));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse replay: " + replayPath, e);
        }
        if (replay == null || replay.gameEvents == null) {
            throw new IllegalArgumentException("No game events in replay: " + replayPath);
        }
        List<Event> events = List.of(replay.gameEvents.getEvents());

        AbilityProfile profile = AbilityProfile.resolve(
                replay.header != null && replay.header.baseBuild != null
                ? replay.header.baseBuild : 75689);
        AbilityMapping                     mapping  = new AbilityMapping(playerId, true, null, profile);
        List<UnitOrder>                    orders   = new ArrayList<>();
        List<TimedIntent>                  intents  = new ArrayList<>();
        List<ReplayCommand.BuildCommand>   builds   = new ArrayList<>();
        List<ReplayCommand.UpgradeCommand> upgrades = new ArrayList<>();
        List<ReplayCommand.MorphCommand>   morphs   = new ArrayList<>();

        for (Event raw : events) {
            if (raw instanceof SelectionDeltaEvent sel) {
                mapping.onSelection(sel);
            } else if (raw instanceof CmdEvent cmd) {
                for (ReplayCommand rc : mapping.process(cmd)) {
                    switch (rc) {
                        case ReplayCommand.Movement m -> orders.add(m.order());
                        case ReplayCommand.IntentCommand i -> intents.add(i.intent());
                        case ReplayCommand.BuildCommand b -> {
                            builds.add(b);
                            convertBuild(b, intents);
                        }
                        case ReplayCommand.UpgradeCommand u -> {
                            upgrades.add(u);
                            convertUpgrade(u, mapping, intents);
                        }
                        case ReplayCommand.MorphCommand m -> {
                            morphs.add(m);
                            convertMorph(m, mapping, intents);
                        }
                        case ReplayCommand.CancelCommand ignored -> {}
                    }
                }
            }
        }

        intents.sort(Comparator.comparingLong(TimedIntent::loop));

        return new ReplayCommandStream(
                Collections.unmodifiableList(orders),
                Collections.unmodifiableList(intents),
                Collections.unmodifiableList(builds),
                Collections.unmodifiableList(upgrades),
                Collections.unmodifiableList(morphs));
    }

    private static void convertBuild(ReplayCommand.BuildCommand b, List<TimedIntent> intents) {
        BuildingType bt = BUILDING_NAME_MAP.get(b.buildingName());
        if (bt != null) {
            intents.add(new TimedIntent(b.loop(), new BuildIntent("worker", bt, b.position())));
        }
    }

    private static void convertUpgrade(ReplayCommand.UpgradeCommand u, AbilityMapping mapping,
                                       List<TimedIntent> intents) {
        UpgradeType ut = UPGRADE_NAME_MAP.get(u.upgradeName());
        if (ut == null) {ut = UPGRADE_NAME_MAP_2.get(u.upgradeName());}
        if (ut != null) {
            String buildingTag = mapping.selectionSize() > 0 ? mapping.selectionSnapshotForTest().get(0) : "unknown";
            intents.add(new TimedIntent(u.loop(), new ResearchIntent(buildingTag, ut)));
        }
    }

    private static void convertMorph(ReplayCommand.MorphCommand m, AbilityMapping mapping,
                                     List<TimedIntent> intents) {
        String unitTag = mapping.selectionSize() > 0 ? mapping.selectionSnapshotForTest().get(0) : "unknown";
        intents.add(new TimedIntent(m.loop(), new MorphIntent(unitTag, m.sourceName(), m.targetName())));
    }

    private static final Map<String, UpgradeType> UPGRADE_NAME_MAP = Map.ofEntries(
            Map.entry("Stimpack", UpgradeType.STIMPACK),
            Map.entry("CombatShield", UpgradeType.COMBAT_SHIELD),
            Map.entry("ConcussiveShells", UpgradeType.CONCUSSIVE_SHELLS),
            Map.entry("BansheeCloak", UpgradeType.BANSHEE_CLOAK),
            Map.entry("BansheeSpeed", UpgradeType.BANSHEE_SPEED),
            Map.entry("BattlecruiserEnableSpecializations", UpgradeType.BATTLECRUISER_SPECIALIZATIONS),
            Map.entry("SmartServos", UpgradeType.SMART_SERVOS),
            Map.entry("HiSecAutoTracking", UpgradeType.HI_SEC_AUTO_TRACKING),
            Map.entry("TerranBuildingArmor", UpgradeType.TERRAN_BUILDING_ARMOR),
            Map.entry("CycloneLockOnDamageUpgrade", UpgradeType.CYCLONE_LOCK_ON_UPGRADE),
            Map.entry("PersonalCloaking", UpgradeType.PERSONAL_CLOAKING),
            Map.entry("DrillClaws", UpgradeType.DRILL_CLAWS),
            Map.entry("TunnelingClaws", UpgradeType.TUNNELING_CLAWS),
            Map.entry("TerranInfantryWeaponsLevel1", UpgradeType.TERRAN_INFANTRY_WEAPONS_1),
            Map.entry("TerranInfantryWeaponsLevel2", UpgradeType.TERRAN_INFANTRY_WEAPONS_2),
            Map.entry("TerranInfantryWeaponsLevel3", UpgradeType.TERRAN_INFANTRY_WEAPONS_3),
            Map.entry("TerranInfantryArmorsLevel1", UpgradeType.TERRAN_INFANTRY_ARMORS_1),
            Map.entry("TerranInfantryArmorsLevel2", UpgradeType.TERRAN_INFANTRY_ARMORS_2),
            Map.entry("TerranInfantryArmorsLevel3", UpgradeType.TERRAN_INFANTRY_ARMORS_3),
            Map.entry("TerranVehicleWeaponsLevel1", UpgradeType.TERRAN_VEHICLE_WEAPONS_1),
            Map.entry("TerranVehicleWeaponsLevel2", UpgradeType.TERRAN_VEHICLE_WEAPONS_2),
            Map.entry("TerranVehicleWeaponsLevel3", UpgradeType.TERRAN_VEHICLE_WEAPONS_3),
            Map.entry("TerranShipWeaponsLevel1", UpgradeType.TERRAN_SHIP_WEAPONS_1),
            Map.entry("TerranShipWeaponsLevel2", UpgradeType.TERRAN_SHIP_WEAPONS_2),
            Map.entry("TerranShipWeaponsLevel3", UpgradeType.TERRAN_SHIP_WEAPONS_3),
            Map.entry("TerranVehicleAndShipArmorsLevel1", UpgradeType.TERRAN_VEHICLE_AND_SHIP_ARMORS_1),
            Map.entry("TerranVehicleAndShipArmorsLevel2", UpgradeType.TERRAN_VEHICLE_AND_SHIP_ARMORS_2),
            Map.entry("TerranVehicleAndShipArmorsLevel3", UpgradeType.TERRAN_VEHICLE_AND_SHIP_ARMORS_3),
            Map.entry("ZerglingMovementSpeed", UpgradeType.ZERGLING_SPEED),
            Map.entry("ZerglingAttackSpeed", UpgradeType.ZERGLING_ATTACK_SPEED),
            Map.entry("GlialReconstitution", UpgradeType.GLIAL_RECONSTITUTION),
            Map.entry("CentrificalHooks", UpgradeType.CENTRIFUGAL_HOOKS),
            Map.entry("Burrow", UpgradeType.BURROW),
            Map.entry("OverlordSpeed", UpgradeType.OVERLORD_SPEED),
            Map.entry("EvolveGroovedSpines", UpgradeType.GROOVED_SPINES),
            Map.entry("EvolveMuscularAugments", UpgradeType.MUSCULAR_AUGMENTS),
            Map.entry("NeuralParasite", UpgradeType.NEURAL_PARASITE),
            Map.entry("InfestorEnergyUpgrade", UpgradeType.INFESTOR_ENERGY),
            Map.entry("ChitinousPlating", UpgradeType.CHITINOUS_PLATING),
            Map.entry("AnabolicSynthesis", UpgradeType.ANABOLIC_SYNTHESIS),
            Map.entry("ZergMeleeWeaponsLevel1", UpgradeType.ZERG_MELEE_WEAPONS_1),
            Map.entry("ZergMeleeWeaponsLevel2", UpgradeType.ZERG_MELEE_WEAPONS_2),
            Map.entry("ZergMeleeWeaponsLevel3", UpgradeType.ZERG_MELEE_WEAPONS_3),
            Map.entry("ZergMissileWeaponsLevel1", UpgradeType.ZERG_MISSILE_WEAPONS_1),
            Map.entry("ZergMissileWeaponsLevel2", UpgradeType.ZERG_MISSILE_WEAPONS_2),
            Map.entry("ZergMissileWeaponsLevel3", UpgradeType.ZERG_MISSILE_WEAPONS_3),
            Map.entry("ZergGroundArmorsLevel1", UpgradeType.ZERG_GROUND_ARMORS_1),
            Map.entry("ZergGroundArmorsLevel2", UpgradeType.ZERG_GROUND_ARMORS_2),
            Map.entry("ZergGroundArmorsLevel3", UpgradeType.ZERG_GROUND_ARMORS_3),
            Map.entry("ZergFlyerWeaponsLevel1", UpgradeType.ZERG_FLYER_WEAPONS_1),
            Map.entry("ZergFlyerWeaponsLevel2", UpgradeType.ZERG_FLYER_WEAPONS_2)
                                                                                  );

    private static final Map<String, UpgradeType> UPGRADE_NAME_MAP_2 = Map.ofEntries(
            Map.entry("ZergFlyerWeaponsLevel3", UpgradeType.ZERG_FLYER_WEAPONS_3),
            Map.entry("ZergFlyerArmorsLevel1", UpgradeType.ZERG_FLYER_ARMORS_1),
            Map.entry("ZergFlyerArmorsLevel2", UpgradeType.ZERG_FLYER_ARMORS_2),
            Map.entry("ZergFlyerArmorsLevel3", UpgradeType.ZERG_FLYER_ARMORS_3),
            Map.entry("WarpGateResearch", UpgradeType.WARP_GATE_RESEARCH),
            Map.entry("BlinkTech", UpgradeType.BLINK),
            Map.entry("Charge", UpgradeType.CHARGE),
            Map.entry("AdeptPiercingAttack", UpgradeType.ADEPT_PIERCING),
            Map.entry("PsiStormTech", UpgradeType.PSI_STORM),
            Map.entry("ExtendedThermalLance", UpgradeType.EXTENDED_THERMAL_LANCE),
            Map.entry("DarkTemplarBlinkUpgrade", UpgradeType.DARK_TEMPLAR_BLINK),
            Map.entry("PhoenixRangeUpgrade", UpgradeType.PHOENIX_RANGE),
            Map.entry("ProtossGroundWeaponsLevel1", UpgradeType.PROTOSS_GROUND_WEAPONS_1),
            Map.entry("ProtossGroundWeaponsLevel2", UpgradeType.PROTOSS_GROUND_WEAPONS_2),
            Map.entry("ProtossGroundWeaponsLevel3", UpgradeType.PROTOSS_GROUND_WEAPONS_3),
            Map.entry("ProtossGroundArmorsLevel1", UpgradeType.PROTOSS_GROUND_ARMORS_1),
            Map.entry("ProtossGroundArmorsLevel2", UpgradeType.PROTOSS_GROUND_ARMORS_2),
            Map.entry("ProtossGroundArmorsLevel3", UpgradeType.PROTOSS_GROUND_ARMORS_3),
            Map.entry("ProtossAirWeaponsLevel1", UpgradeType.PROTOSS_AIR_WEAPONS_1),
            Map.entry("ProtossAirWeaponsLevel2", UpgradeType.PROTOSS_AIR_WEAPONS_2),
            Map.entry("ProtossAirWeaponsLevel3", UpgradeType.PROTOSS_AIR_WEAPONS_3),
            Map.entry("ProtossAirArmorsLevel1", UpgradeType.PROTOSS_AIR_ARMORS_1),
            Map.entry("ProtossAirArmorsLevel2", UpgradeType.PROTOSS_AIR_ARMORS_2),
            Map.entry("ProtossAirArmorsLevel3", UpgradeType.PROTOSS_AIR_ARMORS_3),
            Map.entry("ProtossShieldsLevel1", UpgradeType.PROTOSS_SHIELDS_1),
            Map.entry("ProtossShieldsLevel2", UpgradeType.PROTOSS_SHIELDS_2),
            Map.entry("ProtossShieldsLevel3", UpgradeType.PROTOSS_SHIELDS_3)
                                                                                    );

    private static final Map<String, BuildingType> BUILDING_NAME_MAP = Map.ofEntries(
            Map.entry("Nexus", BuildingType.NEXUS), Map.entry("Pylon", BuildingType.PYLON),
            Map.entry("Gateway", BuildingType.GATEWAY), Map.entry("CyberneticsCore", BuildingType.CYBERNETICS_CORE),
            Map.entry("Assimilator", BuildingType.ASSIMILATOR), Map.entry("RoboticsFacility", BuildingType.ROBOTICS_FACILITY),
            Map.entry("Stargate", BuildingType.STARGATE), Map.entry("Forge", BuildingType.FORGE),
            Map.entry("TwilightCouncil", BuildingType.TWILIGHT_COUNCIL), Map.entry("PhotonCannon", BuildingType.PHOTON_CANNON),
            Map.entry("ShieldBattery", BuildingType.SHIELD_BATTERY), Map.entry("DarkShrine", BuildingType.DARK_SHRINE),
            Map.entry("FleetBeacon", BuildingType.FLEET_BEACON), Map.entry("RoboticsBay", BuildingType.ROBOTICS_BAY),
            Map.entry("TemplarArchives", BuildingType.TEMPLAR_ARCHIVES),
            Map.entry("CommandCenter", BuildingType.COMMAND_CENTER), Map.entry("SupplyDepot", BuildingType.SUPPLY_DEPOT),
            Map.entry("Barracks", BuildingType.BARRACKS), Map.entry("EngineeringBay", BuildingType.ENGINEERING_BAY),
            Map.entry("Armory", BuildingType.ARMORY), Map.entry("MissileTurret", BuildingType.MISSILE_TURRET),
            Map.entry("Bunker", BuildingType.BUNKER), Map.entry("SensorTower", BuildingType.SENSOR_TOWER),
            Map.entry("GhostAcademy", BuildingType.GHOST_ACADEMY), Map.entry("Factory", BuildingType.FACTORY),
            Map.entry("Starport", BuildingType.STARPORT), Map.entry("FusionCore", BuildingType.FUSION_CORE),
            Map.entry("Refinery", BuildingType.REFINERY),
            Map.entry("Hatchery", BuildingType.HATCHERY), Map.entry("SpawningPool", BuildingType.SPAWNING_POOL),
            Map.entry("EvolutionChamber", BuildingType.EVOLUTION_CHAMBER), Map.entry("RoachWarren", BuildingType.ROACH_WARREN),
            Map.entry("BanelingNest", BuildingType.BANELING_NEST), Map.entry("SpineCrawler", BuildingType.SPINE_CRAWLER),
            Map.entry("SporeCrawler", BuildingType.SPORE_CRAWLER), Map.entry("HydraliskDen", BuildingType.HYDRALISK_DEN),
            Map.entry("InfestationPit", BuildingType.INFESTATION_PIT), Map.entry("Spire", BuildingType.SPIRE),
            Map.entry("NydusNetwork", BuildingType.NYDUS_NETWORK), Map.entry("Extractor", BuildingType.EXTRACTOR),
            Map.entry("LurkerDenMP", BuildingType.LURKER_DEN), Map.entry("UltraliskCavern", BuildingType.ULTRALISK_CAVERN),
            Map.entry("BarracksTechLab", BuildingType.BARRACKS), Map.entry("BarracksReactor", BuildingType.BARRACKS),
            Map.entry("FactoryTechLab", BuildingType.FACTORY), Map.entry("FactoryReactor", BuildingType.FACTORY),
            Map.entry("StarportTechLab", BuildingType.STARPORT), Map.entry("StarportReactor", BuildingType.STARPORT),
            Map.entry("CreepTumorQueen", BuildingType.CREEP_TUMOR), Map.entry("CreepTumor", BuildingType.CREEP_TUMOR),
            Map.entry("NydusCanal", BuildingType.NYDUS_CANAL)
                                                                                    );


}
