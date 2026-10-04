package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
import io.quarkmind.sc2.SelectionState;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateful SC2 command interpreter scoped to one player.
 * Owns selection state and maps CmdEvents to ReplayCommands.
 *
 * <p>Ability IDs discovered via AbilityDiscoveryTest from aiarena_protoss PvZ replays.
 * Note: Build commands (probe placing structures) use abilLink=42 (Smart) in bot play,
 * indistinguishable from movement orders. BuildIntent extraction is not attempted here;
 * buildings are synchronised from ground truth in ReplayValidationHarness.
 *
 * <p>Discovered ability table (Nothing_4720936.SC2Replay PvZ):
 * <pre>
 *   userId=0 (Protoss): 42=Smart/Move, 175=TrainProbe, 172=GatewayTrain(idx1=Zealot,idx0=Stalker)
 *   userId=0 (Protoss): 173=RoboticsTrain(idx0=Immortal), 170=WarpGateTrain(hasTP=location)
 *   userId=1 (Zerg):    42=Smart/Move, 193=LarvaTrain(variousIdx), 184=Queen/Inject
 * </pre>
 * Terran coverage added in issue #140 (ABIL_COMMAND_CENTER=155 → SCV, ABIL_BARRACKS=159 → Marine/Marauder).
 */
public class AbilityMapping {

    private static final Logger log = Logger.getLogger(AbilityMapping.class);

    // --- Movement ---
    private static final int ABIL_SMART       = 42;  // Smart/RightClick — move, attack, harvest
    private static final int ABIL_ATTACK_MOVE = 45;  // Attack-move command

    // --- Protoss train (calibrated from 118 oracle replays, 4.9.3) ---
    private static final int ABIL_GATEWAY     = 172; // Gateway normal train
    private static final int ABIL_STARGATE    = 173; // Stargate train (was 174 in bot replays)
    private static final int ABIL_ROBOTICS    = 174; // Robotics Facility train (was 173 in bot replays)
    private static final int ABIL_NEXUS       = 175; // Nexus train (Probe)

    // WarpGate warp-in — abilLink=170 with hasTP=true; treated as movement (location-targeted)
    private static final int ABIL_WARPGATE    = 170;

    // --- Zerg train (from larva, abilLink=193, abilCmdIndex selects unit) ---
    private static final int ABIL_LARVA       = 193;
    // Queen is trained from Hatchery via abilLink=184 abilCmdIndex=1; other indices are macro (inject)
    private static final int ABIL_HATCHERY    = 184;
    // Lair/Hive also trains Queen at abilLink=186 abilCmdIndex=0 (distinct from Hatchery abilLink)
    private static final int ABIL_LAIR        = 186;

    // --- Terran train (AI Arena build 75689) ---
    // Derived from TerranDiscoveryTest: no-target Cmd events cross-referenced across
    // Nothing_4720935 (18m), Tyckles_4721034 (15m), Starlight_4721165 (6m) Terran-wins PvT.
    // Other abilLinks (157, 158) have insufficient cross-replay evidence — logged as unknown.
    private static final int ABIL_COMMAND_CENTER = 155; // idx=0 only → SCV
    private static final int ABIL_BARRACKS       = 159; // idx=0 → Marine, idx=1 → Reaper, idx=3 → Marauder
    private static final int ABIL_FACTORY        = 160; // Factory train (calibrated from oracle replays)
    private static final int ABIL_STARPORT       = 161; // Starport train (calibrated from oracle replays)
    // --- Human replay building placement (from AbilityDiscoveryCalibrationTest, 118 oracle replays) ---
// In human replays, building placement uses distinct per-building abilLinks unlike bot replays
// which use abilLink=42 (Smart). abilLink=170 means Protoss building here, NOT warp-in.
    private static final int ABIL_SCV_BUILD      = 129; // SCV build — abilCmdIndex selects building
    private static final int ABIL_PROBE_BUILD    = 170; // Probe build — same value as ABIL_WARPGATE (mode-dependent)
    private static final int ABIL_DRONE_BUILD    = 183; // Drone build (morph) — abilCmdIndex selects building
    private static final int ABIL_BARRACKS_ADDON = 147;
    private static final int ABIL_FACTORY_ADDON  = 149;
    private static final int ABIL_STARPORT_ADDON = 151;
    private static final int ABIL_WARPGATE_WARPIN = 214; // WarpGate warp-in (human replays)
    private static final int ABIL_ARCHON_MERGE = 267;
    private static final int ABIL_BANELING_MORPH = 73;
    private static final int ABIL_RAVAGER_MORPH = 309;
    private static final int ABIL_BROODLORD_MORPH = 194;
    private static final int ABIL_LURKER_MORPH = 522;
    private static final int ABIL_OVERSEER_MORPH = 221;
    private static final int ABIL_CC_MORPH       = 120;
    private static final int ABIL_CREEP_TUMOR_QUEEN = 260;
    private static final int ABIL_CREEP_TUMOR_SPREAD = 265;
    private static final int ABIL_NYDUS_SPAWN = 268;
    private static final int ABIL_ORACLE_STASIS_WARD = 603;
    private static final int ABIL_MULE_CALLDOWN = 171; // uncalibrated — pending MuleAbilLinkDiscoveryTest

    private static final int ABIL_LAIR_MORPH          = 249; // calibrated: BuildingMorphDiscoveryTest
    private static final int ABIL_HIVE_MORPH          = 250; // calibrated: BuildingMorphDiscoveryTest
    private static final int ABIL_GREATER_SPIRE_MORPH = 252; // calibrated: BuildingMorphDiscoveryTest

    // --- Upgrade research abilLinks (from correlateUpgradesWithUnmappedAbilLinks, 118 oracle replays) ---
    // Terran
    private static final int ABIL_ENGINEERING_BAY   = 162;
    private static final int ABIL_STIMPACK          = 165;
    private static final int ABIL_CONCUSSIVE_SHELLS = 152;
    private static final int ABIL_STARPORT_TECHLAB  = 167;
    private static final int ABIL_FACTORY_TECHLAB   = 166;
    private static final int ABIL_ARMORY            = 169;
    private static final int ABIL_FUSION_CORE       = 235;
    private static final int ABIL_CYCLONE_LOCK_ON   = 148;
    private static final int ABIL_COMBAT_SHIELD     = 124;

    // Zerg
    private static final int ABIL_BANELING_NEST     = 224;
    private static final int ABIL_ROACH_WARREN      = 107;
    private static final int ABIL_EVOLUTION_CHAMBER = 185;
    private static final int ABIL_SPIRE_UPGRADE     = 192;
    private static final int ABIL_HYDRALISK_DEN     = 262;
    private static final int ABIL_MUSCULAR_AUGMENTS = 310;
    private static final int ABIL_ULTRALISK_CAVERN  = 117;
    private static final int ABIL_SPAWNING_POOL     = 190;
    private static final int ABIL_HATCHERY_UPGRADE  = 189;
    // 223 = InfestationPit upgrades (4.9.3) — same abilLink as ABIL_OVERSEER_MORPH_T (tournament)
    // No collision: morph uses idx=0, upgrades use idx=2/3
    private static final int ABIL_INFESTATION_PIT   = 223;
    // Protoss
    private static final int ABIL_FORGE             = 180;
    private static final int ABIL_CYBERNETICS_CORE  = 236;
    private static final int ABIL_TWILIGHT_COUNCIL  = 547;
    private static final int ABIL_TWILIGHT_RESEARCH = 237;
    private static final int ABIL_TEMPLAR_ARCHIVE   = 182;
    private static final int ABIL_ROBOTICS_BAY      = 181;
    private static final int ABIL_DARK_SHRINE       = 177;
    private static final int ABIL_FLEET_BEACON      = 71;

    private static final int UNIT_LINK_DARK_TEMPLAR = 76;


    private static final Map<Integer, UnitType> BARRACKS_UNITS = Map.of(
            0, UnitType.MARINE,
            1, UnitType.REAPER,
            2, UnitType.GHOST,
            3, UnitType.MARAUDER
    );

    // Factory abilCmdIndex → UnitType (calibrated from 118 oracle replays + 500 ladder replays, 4.9.3)
    private static final Map<Integer, UnitType> FACTORY_UNITS = Map.ofEntries(
            Map.entry(1, UnitType.SIEGE_TANK),
            Map.entry(4, UnitType.THOR),
            Map.entry(5, UnitType.HELLION),
            Map.entry(6, UnitType.HELLBAT),
            Map.entry(7, UnitType.CYCLONE),
            Map.entry(24, UnitType.WIDOW_MINE)
    );

    // Starport abilCmdIndex → UnitType (calibrated from 118 oracle replays, 4.9.3)
    private static final Map<Integer, UnitType> STARPORT_UNITS = Map.of(
            0, UnitType.MEDIVAC,
            1, UnitType.BANSHEE,
            2, UnitType.RAVEN,
            3, UnitType.BATTLECRUISER,
            4, UnitType.VIKING,
            6, UnitType.LIBERATOR
    );

    // Gateway abilCmdIndex → UnitType (calibrated from 118 oracle replays + 500 ladder replays, 4.9.3)
    private static final Map<Integer, UnitType> GATEWAY_UNITS = Map.ofEntries(
            Map.entry(0, UnitType.ZEALOT),
            Map.entry(1, UnitType.STALKER),
            Map.entry(4, UnitType.DARK_TEMPLAR),
            Map.entry(5, UnitType.ADEPT),
            Map.entry(6, UnitType.SENTRY)
    );

    // Robotics abilCmdIndex → UnitType (calibrated from 118 oracle replays + 500 ladder replays, 4.9.3)
    private static final Map<Integer, UnitType> ROBOTICS_UNITS = Map.ofEntries(
            Map.entry(0, UnitType.WARP_PRISM),
            Map.entry(1, UnitType.OBSERVER),
            Map.entry(2, UnitType.COLOSSUS),
            Map.entry(3, UnitType.IMMORTAL),
            Map.entry(18, UnitType.DISRUPTOR)
    );

    // Stargate abilCmdIndex → UnitType (calibrated from 118 oracle replays + 500 ladder replays, 4.9.3)
    private static final Map<Integer, UnitType> STARGATE_UNITS = Map.ofEntries(
            Map.entry(0, UnitType.PHOENIX),
            Map.entry(2, UnitType.CARRIER),
            Map.entry(4, UnitType.VOID_RAY),
            Map.entry(8, UnitType.ORACLE),
            Map.entry(9, UnitType.TEMPEST)
    );

    // Zerg larva abilCmdIndex → UnitType (calibrated from 118 oracle replays + 500 ladder replays, 4.9.3)
    private static final Map<Integer, UnitType> LARVA_UNITS = Map.ofEntries(
            Map.entry(0, UnitType.DRONE),
            Map.entry(1, UnitType.ZERGLING),
            Map.entry(2, UnitType.OVERLORD),
            Map.entry(3, UnitType.HYDRALISK),
            Map.entry(4, UnitType.MUTALISK),
            Map.entry(6, UnitType.ULTRALISK),
            Map.entry(9, UnitType.ROACH),
            Map.entry(10, UnitType.INFESTOR),
            Map.entry(11, UnitType.CORRUPTOR),
            Map.entry(12, UnitType.VIPER),
            Map.entry(14, UnitType.SWARM_HOST)
    );
    // Terran SCV build abilCmdIndex → building name (from discovery: 118 oracle replays)
    private static final Map<Integer, String>   SCV_BUILD_BUILDINGS = Map.ofEntries(
            Map.entry(0, "CommandCenter"), Map.entry(1, "SupplyDepot"),
            Map.entry(2, "Refinery"), Map.entry(3, "Barracks"),
            Map.entry(4, "EngineeringBay"), Map.entry(5, "MissileTurret"),
            Map.entry(6, "Bunker"), Map.entry(8, "SensorTower"),
            Map.entry(9, "GhostAcademy"), Map.entry(10, "Factory"),
            Map.entry(11, "Starport"), Map.entry(13, "Armory"),
            Map.entry(15, "FusionCore"));

    // Protoss Probe build abilCmdIndex → building name
    private static final Map<Integer, String> PROBE_BUILD_BUILDINGS = Map.ofEntries(
            Map.entry(0, "Nexus"), Map.entry(1, "Pylon"),
            Map.entry(2, "Assimilator"), Map.entry(3, "Gateway"),
            Map.entry(4, "Forge"), Map.entry(5, "FleetBeacon"),
            Map.entry(6, "TwilightCouncil"), Map.entry(7, "PhotonCannon"),
            Map.entry(9, "Stargate"), Map.entry(10, "TemplarArchive"),
            Map.entry(11, "DarkShrine"), Map.entry(12, "RoboticsBay"),
            Map.entry(13, "RoboticsFacility"), Map.entry(14, "CyberneticsCore"),
            Map.entry(15, "ShieldBattery"));

    // Zerg Drone build abilCmdIndex → building name
    private static final Map<Integer, String> DRONE_BUILD_BUILDINGS = Map.ofEntries(
            Map.entry(0, "Hatchery"), Map.entry(2, "Extractor"),
            Map.entry(3, "SpawningPool"), Map.entry(4, "EvolutionChamber"),
            Map.entry(5, "HydraliskDen"), Map.entry(6, "Spire"),
            Map.entry(7, "UltraliskCavern"), Map.entry(8, "InfestationPit"),
            Map.entry(9, "NydusNetwork"), Map.entry(10, "BanelingNest"),
            Map.entry(11, "LurkerDenMP"), Map.entry(13, "RoachWarren"),
            Map.entry(14, "SpineCrawler"), Map.entry(15, "SporeCrawler"));

    // Terran addon maps
    private static final Map<Integer, String> BARRACKS_ADDON_MAP = Map.of(0, "BarracksTechLab", 1, "BarracksReactor");
    private static final Map<Integer, String> FACTORY_ADDON_MAP  = Map.of(0, "FactoryTechLab", 1, "FactoryReactor");
    private static final Map<Integer, String> STARPORT_ADDON_MAP = Map.of(0, "StarportTechLab", 1, "StarportReactor");
    private static final Map<Integer, String> CC_MORPH_TARGETS   = Map.of(
            0, "PlanetaryFortress",
            1, "OrbitalCommand"
                                                                         );


    // --- Upgrade research maps (abilCmdIndex → upgrade pythonName) ---
    // Engineering Bay (abilLink=162) — abilityId 650-658 sequential (ocraft Abilities enum)
    private static final Map<Integer, String> ENGINEERING_BAY_UPGRADES = Map.ofEntries(
            Map.entry(0, "HiSecAutoTracking"),
            Map.entry(1, "TerranBuildingArmor"),
            Map.entry(2, "TerranInfantryWeaponsLevel1"),
            Map.entry(3, "TerranInfantryWeaponsLevel2"),
            Map.entry(4, "TerranInfantryWeaponsLevel3"),
            Map.entry(6, "TerranInfantryArmorsLevel1"),
            Map.entry(7, "TerranInfantryArmorsLevel2"),
            Map.entry(8, "TerranInfantryArmorsLevel3")
    );

    // StarportTechLab (abilLink=167) — n=8 Cloak, n=5 LibRange, n=1 MedivacSpeed (domain-consistent)
    private static final Map<Integer, String> STARPORT_TECHLAB_UPGRADES = Map.ofEntries(
            Map.entry(0, "BansheeCloak"),
            Map.entry(3, "RavenCorvidReactor"),
            Map.entry(9, "BansheeSpeed"),
            Map.entry(14, "MedivacIncreaseSpeedBoost"),
            Map.entry(15, "LiberatorAGRangeUpgrade")
    );

    // FactoryTechLab (abilLink=166) — n=6 HiCap, n=3 SmartServos, n=7 DrillClaws (100% hit)
    private static final Map<Integer, String> FACTORY_TECHLAB_UPGRADES = Map.of(
            1, "HighCapacityBarrels",
            4, "DrillClaws",
            6, "SmartServos"
    );

    // Armory (abilLink=169) — n=17 VehShipArmor1; VehWeap1/ShipWeap1 inferred from tiered pattern
    private static final Map<Integer, String> ARMORY_UPGRADES = Map.ofEntries(
            Map.entry(5, "TerranVehicleWeaponsLevel1"),
            Map.entry(6, "TerranVehicleWeaponsLevel2"),
            Map.entry(7, "TerranVehicleWeaponsLevel3"),
            Map.entry(11, "TerranShipWeaponsLevel1"),
            Map.entry(12, "TerranShipWeaponsLevel2"),
            Map.entry(13, "TerranShipWeaponsLevel3"),
            Map.entry(14, "TerranVehicleAndShipArmorsLevel1"),
            Map.entry(15, "TerranVehicleAndShipArmorsLevel2"),
            Map.entry(16, "TerranVehicleAndShipArmorsLevel3")
    );

    // EvolutionChamber (abilLink=185) — n=15 Melee1, n=19 GrndArmor1(alt), n=23 Missile1
    private static final Map<Integer, String> EVOLUTION_CHAMBER_UPGRADES = Map.ofEntries(
            Map.entry(0, "ZergMeleeWeaponsLevel1"),
            Map.entry(1, "ZergMeleeWeaponsLevel2"),
            Map.entry(2, "ZergMeleeWeaponsLevel3"),
            Map.entry(3, "ZergGroundArmorsLevel1"),
            Map.entry(4, "ZergGroundArmorsLevel2"),
            Map.entry(5, "ZergGroundArmorsLevel3"),
            Map.entry(6, "ZergMissileWeaponsLevel1"),
            Map.entry(7, "ZergMissileWeaponsLevel2"),
            Map.entry(8, "ZergMissileWeaponsLevel3")
    );

    // Spire (abilLink=192) — n=4 FlyWeap1, n=3 FlyArmor1; Level3 inferred from tiered pattern
    private static final Map<Integer, String> SPIRE_UPGRADES = Map.ofEntries(
            Map.entry(0, "ZergFlyerWeaponsLevel1"),
            Map.entry(1, "ZergFlyerWeaponsLevel2"),
            Map.entry(2, "ZergFlyerWeaponsLevel3"),
            Map.entry(3, "ZergFlyerArmorsLevel1"),
            Map.entry(4, "ZergFlyerArmorsLevel2"),
            Map.entry(5, "ZergFlyerArmorsLevel3")
    );

    // Forge (abilLink=180) — n=18 GrndWeap1(alt); GrndArmor1 freq n=12 at 180/3; Shields L2/L3 by pattern
    private static final Map<Integer, String> FORGE_UPGRADES = Map.ofEntries(
            Map.entry(0, "ProtossGroundWeaponsLevel1"),
            Map.entry(1, "ProtossGroundWeaponsLevel2"),
            Map.entry(2, "ProtossGroundWeaponsLevel3"),
            Map.entry(3, "ProtossGroundArmorsLevel1"),
            Map.entry(4, "ProtossGroundArmorsLevel2"),
            Map.entry(5, "ProtossGroundArmorsLevel3"),
            Map.entry(6, "ProtossShieldsLevel1"),
            Map.entry(7, "ProtossShieldsLevel2"),
            Map.entry(8, "ProtossShieldsLevel3")
    );

    // CyberneticsCore (abilLink=236) — n=48 WarpGate; AirWeap1 freq n=6; AirArmor pattern-inferred
    private static final Map<Integer, String> CYBERNETICS_CORE_UPGRADES = Map.ofEntries(
            Map.entry(0, "ProtossAirWeaponsLevel1"),
            Map.entry(1, "ProtossAirWeaponsLevel2"),
            Map.entry(2, "ProtossAirWeaponsLevel3"),
            Map.entry(3, "ProtossAirArmorsLevel1"),
            Map.entry(4, "ProtossAirArmorsLevel2"),
            Map.entry(5, "ProtossAirArmorsLevel3"),
            Map.entry(6, "WarpGateResearch")
    );

    // TwilightCouncil research (abilLink=237) — Charge/Blink/AdeptPiercing share one abilLink
    private static final Map<Integer, String> TWILIGHT_RESEARCH_UPGRADES = Map.of(
            0, "Charge",
            1, "BlinkTech",
            2, "AdeptPiercingAttack"
    );

    // BarracksTechLab unified (abilLink=165) — tournament replays use one abilLink for all research
    private static final Map<Integer, String> BARRACKS_TECHLAB_UPGRADES = Map.of(
            0, "Stimpack",
            1, "ShieldWall",
            2, "PunisherGrenades"
    );


    // WarpGate warp-in (human replays) abilCmdIndex → UnitType
    private static final Map<Integer, UnitType> WARPGATE_WARPIN_UNITS = Map.of(
            0, UnitType.ZEALOT, 1, UnitType.STALKER,
            3, UnitType.HIGH_TEMPLAR, 4, UnitType.DARK_TEMPLAR,
            5, UnitType.SENTRY, 6, UnitType.ADEPT);


    private final int userId;  // 0-indexed game event userId = (playerId - 1)
    private final SelectionState selection = new SelectionState();
    private final boolean        humanReplay;
    private final Map<String, Integer> tagToUnitLink = new HashMap<>();

    private final Race           race;
    private final AbilityProfile profile;
    private       boolean        warpGateResearchEmitted = false;


    public AbilityMapping(int playerId) {
        this(playerId, false, null);
    }

    public AbilityMapping(int playerId, boolean humanReplay) {
        this(playerId, humanReplay, null);
    }

    public AbilityMapping(int playerId, boolean humanReplay, Race race) {
        this(playerId, humanReplay, race, AbilityProfile.V4_9_3);
    }

    public AbilityMapping(int playerId, boolean humanReplay, Race race, AbilityProfile profile) {
        this.userId = playerId - 1;
        this.humanReplay = humanReplay;
        this.race = race;
        this.profile = profile;
    }

    public void onSelection(SelectionDeltaEvent event) {
        if (event.getUserId() != userId) {return;}
        var delta = event.getDelta();
        if (delta == null) {
            selection.clear();
            tagToUnitLink.clear();
            return;
        }

        var    removeMask = delta.getRemoveMask();
        String variant    = removeMask != null ? removeMask.value1 : null;

        if (variant == null || "None".equals(variant)) {
            // carry forward — no removal
        } else if ("ZeroIndices".equals(variant)) {
            if (removeMask.value2 instanceof Integer[] indices) {
                selection.retainOnly(toPrimitiveInts(indices));
            } else {
                selection.clear();
            }
        } else if ("Mask".equals(variant)) {
            if (removeMask.value2 instanceof hu.belicza.andras.util.type.BitArray bitArray) {
                selection.removeIf(i -> i < bitArray.getCount() && bitArray.getBit(i));
            } else {
                log.debugf("[SELECTION] Mask variant has unexpected payload type: %s", removeMask.value2);
            }
        } else if ("OneIndices".equals(variant)) {
            if (removeMask.value2 instanceof Integer[] indices) {
                for (int i = indices.length - 1; i >= 0; i--) {
                    selection.removeAt(indices[i]);
                }
            } else {
                log.debugf("[SELECTION] OneIndices variant has unexpected payload type: %s", removeMask.value2);
            }
        } else {
            log.debugf("[SELECTION] Unknown removeMask variant '%s' — treating as full clear", variant);
            selection.clear();
        }

        // Sync unitLink map with any removed tags
        tagToUnitLink.keySet().retainAll(new HashSet<>(selection.snapshot()));

        // Zip addSubgroups with addUnitTags to track unitLink per tag
        var subgroups = delta.getAddSubgroups();
        var addTags   = delta.getAddUnitTags();
        if (addTags != null) {
            int sgIdx       = 0;
            int sgRemaining = 0;
            int currentLink = -1;
            if (subgroups != null && subgroups.length > 0) {
                Integer link  = subgroups[0].getUnitLink();
                Integer count = subgroups[0].getCount();
                currentLink = link != null ? link : -1;
                sgRemaining = count != null ? count : 0;
                sgIdx       = 1;
            }
            for (Integer rawTag : addTags) {
                if (rawTag != null) {
                    String tag = GameEventStream.decodeTag(rawTag);
                    selection.addTag(tag);
                    if (currentLink >= 0) {
                        tagToUnitLink.put(tag, currentLink);
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

    public List<ReplayCommand> process(CmdEvent event) {
        if (event.getUserId() != userId) {return List.of();}
        Integer abilLink = event.getAbilLink();
        if (abilLink == null) {return List.of();}
        int idx = Objects.requireNonNullElse(event.getAbilCmdIndex(), 0);

        // Human-mode commands (builds, warp-ins, archon merge) are self-identifying
        // via abilLink and don't need selection state — try before the selection guard.
        if (humanReplay) {
            List<ReplayCommand> result = dispatchHuman(abilLink, idx, event, event.getLoop());
            if (result != null) {return result;}
        }

        if (selection.isEmpty() && !humanReplay) {return List.of();}
        return dispatch(abilLink, idx, event);
    }

    public void reset() {
        selection.clear();
        tagToUnitLink.clear();
    }

    /** Package-private — used by AbilityMappingTest to prime selection without replay parsing. */
    void setSelectionForTest(int forUserId, List<String> tags) {
        if (forUserId == this.userId) {
            selection.clear();
            tags.forEach(selection::addTag);
        }
    }

    /** Package-private — returns a snapshot of the current selection for test assertions. */
    List<String> selectionSnapshotForTest() {
        return selection.snapshot();
    }

    int selectionSize() {
        return selection.size();
    }


    private List<ReplayCommand> dispatch(int abilLink, int idx, CmdEvent event) {
        long loop = event.getLoop();

        return switch (abilLink) {
            case ABIL_SMART, ABIL_ATTACK_MOVE, ABIL_WARPGATE -> moveOrders(event, loop);

            case ABIL_NEXUS -> isRace(Race.PROTOSS) ? trainIntent(loop, UnitType.PROBE) : unknown(abilLink, idx);

            case ABIL_GATEWAY -> {
                if (!isRace(Race.PROTOSS)) {yield unknown(abilLink, idx);}
                UnitType unit = GATEWAY_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_ROBOTICS -> {
                if (!isRace(Race.PROTOSS)) {yield unknown(abilLink, idx);}
                UnitType unit = ROBOTICS_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_STARGATE -> {
                if (!isRace(Race.PROTOSS)) {yield unknown(abilLink, idx);}
                UnitType unit = STARGATE_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_LARVA -> {
                if (!isRace(Race.ZERG)) {yield unknown(abilLink, idx);}
                UnitType unit = LARVA_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_HATCHERY -> {
                if (!isRace(Race.ZERG)) {yield unknown(abilLink, idx);}
                yield idx == 1 ? trainIntent(loop, UnitType.QUEEN) : List.of();
            }

            case ABIL_LAIR -> {
                if (!isRace(Race.ZERG)) {yield unknown(abilLink, idx);}
                yield idx == 0 ? trainIntent(loop, UnitType.QUEEN) : List.of();
            }

            case ABIL_COMMAND_CENTER -> {
                if (!isRace(Race.TERRAN)) {yield unknown(abilLink, idx);}
                yield idx == 0 ? trainIntent(loop, UnitType.SCV) : unknown(abilLink, idx);
            }

            case ABIL_BARRACKS -> {
                if (!isRace(Race.TERRAN)) {yield unknown(abilLink, idx);}
                UnitType unit = BARRACKS_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_FACTORY -> {
                if (!isRace(Race.TERRAN)) {yield unknown(abilLink, idx);}
                UnitType unit = FACTORY_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_STARPORT -> {
                if (!isRace(Race.TERRAN)) {yield unknown(abilLink, idx);}
                UnitType unit = STARPORT_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            default -> unknown(abilLink, idx);
        };
    }

    private boolean isRace(Race expected) {
        return race == null || race == expected;
    }

    private List<ReplayCommand> dispatchHuman(int abilLink, int idx, CmdEvent event, long loop) {
        AbilityDispatch override = profile.overrides().get(abilLink);
        if (override != null) {
            List<ReplayCommand> result = override.dispatch(idx, event, loop, race, resolveSelectedUnitLink());
            if (result != null) { return result; }
        }
        return switch (abilLink) {
            case ABIL_SCV_BUILD -> isRace(Race.TERRAN) ? buildCommand(loop, SCV_BUILD_BUILDINGS.get(idx), event) : null;
            case ABIL_PROBE_BUILD -> {
                if (isRace(Race.PROTOSS)) {yield buildCommand(loop, PROBE_BUILD_BUILDINGS.get(idx), event);}
                if (isRace(Race.TERRAN) && idx == 0) {yield upgradeCommand(loop, "PersonalCloaking");}
                yield null;
            }
            case ABIL_DRONE_BUILD -> isRace(Race.ZERG) ? buildCommand(loop, DRONE_BUILD_BUILDINGS.get(idx), event) : null;
            case ABIL_BARRACKS_ADDON -> isRace(Race.TERRAN) ? buildCommand(loop, BARRACKS_ADDON_MAP.get(idx), event) : null;
            case ABIL_FACTORY_ADDON -> isRace(Race.TERRAN) ? buildCommand(loop, FACTORY_ADDON_MAP.get(idx), event) : null;
            case ABIL_STARPORT_ADDON -> isRace(Race.TERRAN) ? buildCommand(loop, STARPORT_ADDON_MAP.get(idx), event) : null;
            case ABIL_WARPGATE_WARPIN -> {
                if (!isRace(Race.PROTOSS)) {yield null;}
                UnitType unit = WARPGATE_WARPIN_UNITS.get(idx);
                if (unit == null) {yield null;}
                List<ReplayCommand> train = trainIntent(loop, unit);
                if (!warpGateResearchEmitted) {
                    warpGateResearchEmitted = true;
                    var result = new ArrayList<ReplayCommand>();
                    long inferredStart = Math.max(0, loop - SC2Data.upgradeTimeInLoops(UpgradeType.WARP_GATE_RESEARCH));
                    result.add(new ReplayCommand.UpgradeCommand(inferredStart, "WarpGateResearch"));
                    result.addAll(train);
                    yield result;
                }
                yield train;
            }
            case ABIL_ARCHON_MERGE -> {
                if (!isRace(Race.PROTOSS)) {yield null;}
                String archonSource = resolveArchonSource();
                yield List.of(new ReplayCommand.MorphCommand(loop, archonSource, "Archon"));
            }
            case ABIL_BANELING_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Zergling", "Baneling")) : null;
            case ABIL_RAVAGER_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Roach", "Ravager")) : null;
            case ABIL_BROODLORD_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Corruptor", "BroodLord")) : null;
            case ABIL_LURKER_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Hydralisk", "Lurker")) : null;
            case ABIL_OVERSEER_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Overlord", "Overseer")) : null;
            case ABIL_CC_MORPH -> {
                if (!isRace(Race.TERRAN)) {yield null;}
                String target = CC_MORPH_TARGETS.get(idx);
                yield target != null ? List.of(new ReplayCommand.MorphCommand(loop, "CommandCenter", target)) : null;
            }
            case ABIL_CREEP_TUMOR_QUEEN -> isRace(Race.ZERG)
                ? buildCommand(loop, "CreepTumorQueen", event) : null;
            case ABIL_CREEP_TUMOR_SPREAD -> isRace(Race.ZERG)
                ? buildCommand(loop, "CreepTumor", event) : null;
            case ABIL_NYDUS_SPAWN -> isRace(Race.ZERG)
                ? buildCommand(loop, "NydusCanal", event) : null;
            case ABIL_ORACLE_STASIS_WARD -> isRace(Race.PROTOSS)
                ? buildCommand(loop, "OracleStasisTrap", event) : null;
            case ABIL_MULE_CALLDOWN -> isRace(Race.TERRAN) ? trainIntent(loop, UnitType.MULE) : null;
            case ABIL_LAIR_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Hatchery", "Lair")) : null;
            case ABIL_HIVE_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Lair", "Hive")) : null;
            case ABIL_GREATER_SPIRE_MORPH -> isRace(Race.ZERG) ? List.of(new ReplayCommand.MorphCommand(loop, "Spire", "GreaterSpire")) : null;
            // --- Upgrade research ---
            // Terran
            case ABIL_ENGINEERING_BAY -> isRace(Race.TERRAN) ? upgradeCommand(loop, ENGINEERING_BAY_UPGRADES.get(idx)) : null;
            case ABIL_STIMPACK -> isRace(Race.TERRAN) ? upgradeCommand(loop, BARRACKS_TECHLAB_UPGRADES.get(idx)) : null;
            case ABIL_CONCUSSIVE_SHELLS -> isRace(Race.TERRAN) && idx == 0 ? upgradeCommand(loop, "PunisherGrenades") : null;
            case ABIL_COMBAT_SHIELD -> isRace(Race.TERRAN) && idx == 1 ? upgradeCommand(loop, "ShieldWall") : null;
            case ABIL_STARPORT_TECHLAB -> isRace(Race.TERRAN) ? upgradeCommand(loop, STARPORT_TECHLAB_UPGRADES.get(idx)) : null;
            case ABIL_FACTORY_TECHLAB -> isRace(Race.TERRAN) ? upgradeCommand(loop, FACTORY_TECHLAB_UPGRADES.get(idx)) : null;
            case ABIL_ARMORY -> isRace(Race.TERRAN) ? upgradeCommand(loop, ARMORY_UPGRADES.get(idx)) : null;
            case ABIL_FUSION_CORE -> isRace(Race.TERRAN) && idx == 0 ? upgradeCommand(loop, "BattlecruiserEnableSpecializations") : null;
            case ABIL_CYCLONE_LOCK_ON -> isRace(Race.TERRAN) && idx == 0 ? upgradeCommand(loop, "CycloneLockOnDamageUpgrade") : null;
            // Zerg
            case ABIL_BANELING_NEST -> isRace(Race.ZERG) && idx == 0 ? upgradeCommand(loop, "CentrificalHooks") : null;
            case ABIL_ROACH_WARREN -> {
                if (!isRace(Race.ZERG)) {yield null;}
                yield switch (idx) {
                    case 1 -> upgradeCommand(loop, "GlialReconstitution");
                    case 2 -> upgradeCommand(loop, "TunnelingClaws");
                    default -> null;
                };
            }
            case ABIL_EVOLUTION_CHAMBER -> isRace(Race.ZERG) ? upgradeCommand(loop, EVOLUTION_CHAMBER_UPGRADES.get(idx)) : null;
            case ABIL_SPIRE_UPGRADE -> isRace(Race.ZERG) ? upgradeCommand(loop, SPIRE_UPGRADES.get(idx)) : null;
            case ABIL_HYDRALISK_DEN -> isRace(Race.ZERG) && idx == 0 ? upgradeCommand(loop, "EvolveGroovedSpines") : null;
            case ABIL_MUSCULAR_AUGMENTS -> isRace(Race.ZERG) && idx == 0 ? upgradeCommand(loop, "EvolveMuscularAugments") : null;
            case ABIL_ULTRALISK_CAVERN -> isRace(Race.ZERG) ? switch (idx) {
                case 0 -> upgradeCommand(loop, "AnabolicSynthesis");
                case 2 -> upgradeCommand(loop, "ChitinousPlating");
                default -> null;
            } : null;
            case ABIL_SPAWNING_POOL -> isRace(Race.ZERG) ? switch (idx) {
                case 0 -> upgradeCommand(loop, "zerglingattackspeed");
                case 1 -> upgradeCommand(loop, "zerglingmovementspeed");
                default -> null;
            } : null;
            case ABIL_INFESTATION_PIT -> isRace(Race.ZERG) ? switch (idx) {
                case 2 -> upgradeCommand(loop, "InfestorEnergyUpgrade");
                case 3 -> upgradeCommand(loop, "NeuralParasite");
                default -> null;
            } : null;
            case ABIL_HATCHERY_UPGRADE -> isRace(Race.ZERG) ? switch (idx) {
                case 1 -> upgradeCommand(loop, "overlordspeed");
                case 3 -> upgradeCommand(loop, "Burrow");
                default -> null;
            } : null;
            // Protoss
            case ABIL_FORGE -> isRace(Race.PROTOSS) ? upgradeCommand(loop, FORGE_UPGRADES.get(idx)) : null;
            case ABIL_CYBERNETICS_CORE -> isRace(Race.PROTOSS) ? upgradeCommand(loop, CYBERNETICS_CORE_UPGRADES.get(idx)) : null;
            case ABIL_TWILIGHT_COUNCIL -> isRace(Race.PROTOSS) && idx == 0 ? upgradeCommand(loop, "Charge") : null;
            case ABIL_TWILIGHT_RESEARCH -> isRace(Race.PROTOSS) ? upgradeCommand(loop, TWILIGHT_RESEARCH_UPGRADES.get(idx)) : null;
            case ABIL_TEMPLAR_ARCHIVE -> isRace(Race.PROTOSS) && idx == 4 ? upgradeCommand(loop, "PsiStormTech") : null;
            case ABIL_ROBOTICS_BAY -> isRace(Race.PROTOSS) && idx == 5 ? upgradeCommand(loop, "ExtendedThermalLance") : null;
            case ABIL_DARK_SHRINE -> isRace(Race.PROTOSS) && idx == 0 ? upgradeCommand(loop, "DarkTemplarBlinkUpgrade") : null;
            case ABIL_FLEET_BEACON -> isRace(Race.PROTOSS) && idx == 2 ? upgradeCommand(loop, "PhoenixRangeUpgrade") : null;
            case ABIL_ROBOTICS -> isRace(Race.PROTOSS) && idx == 6 ? upgradeCommand(loop, "WarpGateResearch") : null;
            default -> null;
        };
    }

    private List<ReplayCommand> upgradeCommand(long loop, String upgradeName) {
        if (upgradeName == null) {return null;}
        return List.of(new ReplayCommand.UpgradeCommand(loop, upgradeName));
    }

    private List<ReplayCommand> buildCommand(long loop, String buildingName, CmdEvent event) {
        if (buildingName == null) {return List.of();}
        var tp = event.getTargetPoint();
        if (tp == null) {return List.of();}
        float x = tp.getXFloat() * 2;
        float y = tp.getYFloat() * 2;
        return List.of(new ReplayCommand.BuildCommand(loop, buildingName, new Point2d(x, y)));
    }

    private String resolveArchonSource() {
        for (String tag : selection.snapshot()) {
            Integer link = tagToUnitLink.get(tag);
            if (link != null && link == UNIT_LINK_DARK_TEMPLAR) {
                return "DarkTemplar";
            }
        }
        return "HighTemplar";
    }

    private int resolveSelectedUnitLink() {
        String first = selection.first();
        if (first == null) {return -1;}
        Integer link = tagToUnitLink.get(first);
        return link != null ? link : -1;
    }


    private List<ReplayCommand> trainIntent(long loop, UnitType unitType) {
        String buildingTag = selection.first();
        return List.of(new ReplayCommand.IntentCommand(
                new TimedIntent(loop, new TrainIntent(buildingTag, unitType))));
    }

    private List<ReplayCommand> moveOrders(CmdEvent event, long loop) {
        var                 tu     = event.getTargetUnit();
        var                 tp     = event.getTargetPoint();
        List<ReplayCommand> orders = new ArrayList<>(selection.size());
        for (String tag : selection.snapshot()) {
            if (tu != null && tu.getTag() != null) {
                orders.add(new ReplayCommand.Movement(
                        new UnitOrder(tag, loop, null, GameEventStream.decodeTag(tu.getTag()))));
            } else if (tp != null) {
                // Game event target points are at half the tracker event coordinate scale
                float x = tp.getXFloat() * 2;
                float y = tp.getYFloat() * 2;
                if (x >= 0 && x <= 512 && y >= 0 && y <= 512) {
                    orders.add(new ReplayCommand.Movement(
                            new UnitOrder(tag, loop, new Point2d(x, y), null)));
                }
            } else {
                log.debugf("[ABILITY] Move cmd at loop %d has no target — skipped for %s", loop, tag);
            }
        }
        return orders;
    }

    private List<ReplayCommand> unknown(int abilLink, int idx) {
        log.debugf("[ABILITY] Unknown abilLink=%d abilCmdIndex=%d — skipped", abilLink, idx);
        return List.of();
    }

    private static int[] toPrimitiveInts(Integer[] boxed) {
        int[] result = new int[boxed.length];
        for (int i = 0; i < boxed.length; i++) result[i] = boxed[i];
        return result;
    }
}
