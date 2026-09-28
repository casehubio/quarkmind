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


    private static final Map<Integer, UnitType> BARRACKS_UNITS = Map.of(
            0, UnitType.MARINE,
            1, UnitType.REAPER,
            3, UnitType.MARAUDER
    );

    // Factory abilCmdIndex → UnitType (calibrated from 118 oracle replays, 4.9.3)
    private static final Map<Integer, UnitType> FACTORY_UNITS = Map.ofEntries(
            Map.entry(1, UnitType.SIEGE_TANK),
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

    // Gateway abilCmdIndex → UnitType (calibrated from 118 oracle replays, 4.9.3)
    private static final Map<Integer, UnitType> GATEWAY_UNITS = Map.of(
            0, UnitType.ZEALOT,
            1, UnitType.STALKER,
            5, UnitType.ADEPT
    );

    // Robotics abilCmdIndex → UnitType (calibrated from 118 oracle replays, 4.9.3)
    private static final Map<Integer, UnitType> ROBOTICS_UNITS = Map.of(
            0, UnitType.WARP_PRISM,
            1, UnitType.OBSERVER,
            2, UnitType.COLOSSUS,
            3, UnitType.IMMORTAL
    );

    // Stargate abilCmdIndex → UnitType (calibrated from 118 oracle replays, 4.9.3)
    private static final Map<Integer, UnitType> STARGATE_UNITS = Map.of(
            0, UnitType.PHOENIX,
            2, UnitType.CARRIER,
            8, UnitType.ORACLE
    );

    // Zerg larva abilCmdIndex → UnitType (calibrated from 118 oracle replays, 4.9.3)
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

    // WarpGate warp-in (human replays) abilCmdIndex → UnitType
    private static final Map<Integer, UnitType> WARPGATE_WARPIN_UNITS = Map.of(
            0, UnitType.ZEALOT, 1, UnitType.STALKER,
            3, UnitType.HIGH_TEMPLAR, 4, UnitType.DARK_TEMPLAR,
            5, UnitType.SENTRY, 6, UnitType.ADEPT);


    private final int userId;  // 0-indexed game event userId = (playerId - 1)
    private final SelectionState selection = new SelectionState();
    private final boolean        humanReplay;
    private final Race           race;
    private       boolean        warpGateResearchEmitted = false;


    public AbilityMapping(int playerId) {
        this(playerId, false, null);
    }

    public AbilityMapping(int playerId, boolean humanReplay) {
        this(playerId, humanReplay, null);
    }

    public AbilityMapping(int playerId, boolean humanReplay, Race race) {
        this.userId = playerId - 1;
        this.humanReplay = humanReplay;
        this.race = race;
    }

    public void onSelection(SelectionDeltaEvent event) {
        if (event.getUserId() != userId) return;
        var delta = event.getDelta();
        if (delta == null) {
            selection.clear();
            return;
        }

        var removeMask = delta.getRemoveMask();
        String variant = removeMask != null ? removeMask.value1 : null;

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

        if (delta.getAddUnitTags() != null) {
            for (Integer rawTag : delta.getAddUnitTags()) {
                if (rawTag != null) {
                    selection.addTag(GameEventStream.decodeTag(rawTag));
                }
            }
        }
    }

    public List<ReplayCommand> process(CmdEvent event) {
        if (event.getUserId() != userId) return List.of();
        if (selection.isEmpty()) return List.of();
        Integer abilLink = event.getAbilLink();
        if (abilLink == null) return List.of();
        int idx = Objects.requireNonNullElse(event.getAbilCmdIndex(), 0);
        return dispatch(abilLink, idx, event);
    }

    public void reset() {
        selection.clear();
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

    private List<ReplayCommand> dispatch(int abilLink, int idx, CmdEvent event) {
        long loop = event.getLoop();

        if (humanReplay) {
            List<ReplayCommand> result = dispatchHuman(abilLink, idx, event, loop);
            if (result != null) {return result;}
        }

        return switch (abilLink) {
            case ABIL_SMART, ABIL_ATTACK_MOVE, ABIL_WARPGATE -> moveOrders(event, loop);

            case ABIL_NEXUS -> isRace(Race.PROTOSS) ? trainIntent(loop, UnitType.PROBE) : unknown(abilLink, idx);

            case ABIL_GATEWAY -> {
                if (!isRace(Race.PROTOSS)) yield unknown(abilLink, idx);
                UnitType unit = GATEWAY_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_ROBOTICS -> {
                if (!isRace(Race.PROTOSS)) yield unknown(abilLink, idx);
                UnitType unit = ROBOTICS_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_STARGATE -> {
                if (!isRace(Race.PROTOSS)) yield unknown(abilLink, idx);
                UnitType unit = STARGATE_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_LARVA -> {
                if (!isRace(Race.ZERG)) yield unknown(abilLink, idx);
                UnitType unit = LARVA_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_HATCHERY -> {
                if (!isRace(Race.ZERG)) yield unknown(abilLink, idx);
                yield idx == 1 ? trainIntent(loop, UnitType.QUEEN) : List.of();
            }

            case ABIL_COMMAND_CENTER -> {
                if (!isRace(Race.TERRAN)) yield unknown(abilLink, idx);
                yield idx == 0 ? trainIntent(loop, UnitType.SCV) : unknown(abilLink, idx);
            }

            case ABIL_BARRACKS -> {
                if (!isRace(Race.TERRAN)) yield unknown(abilLink, idx);
                UnitType unit = BARRACKS_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_FACTORY -> {
                if (!isRace(Race.TERRAN)) yield unknown(abilLink, idx);
                UnitType unit = FACTORY_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_STARPORT -> {
                if (!isRace(Race.TERRAN)) yield unknown(abilLink, idx);
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
        return switch (abilLink) {
            case ABIL_SCV_BUILD -> isRace(Race.TERRAN) ? buildCommand(loop, SCV_BUILD_BUILDINGS.get(idx), event) : null;
            case ABIL_PROBE_BUILD -> isRace(Race.PROTOSS) ? buildCommand(loop, PROBE_BUILD_BUILDINGS.get(idx), event) : null;
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
            case ABIL_ARCHON_MERGE -> isRace(Race.PROTOSS) ? List.of(new ReplayCommand.MorphCommand(loop, "HighTemplar", "Archon")) : null;
            default -> null;
        };
    }

    private List<ReplayCommand> buildCommand(long loop, String buildingName, CmdEvent event) {
        if (buildingName == null) {return List.of();}
        var tp = event.getTargetPoint();
        if (tp == null) {return List.of();}
        float x = tp.getXFloat() * 2;
        float y = tp.getYFloat() * 2;
        return List.of(new ReplayCommand.BuildCommand(loop, buildingName, new Point2d(x, y)));
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
