package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
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

    // --- Protoss train ---
    private static final int ABIL_GATEWAY     = 172; // Gateway normal train (abilCmdIndex selects unit)
    private static final int ABIL_ROBOTICS    = 173; // Robotics Facility train
    private static final int ABIL_NEXUS       = 175; // Nexus train (Probe)
    private static final int ABIL_STARGATE    = 174; // Stargate train

    // WarpGate warp-in — abilLink=170 with hasTP=true; treated as movement (location-targeted)
    private static final int ABIL_WARPGATE    = 170;

    // --- Zerg train (from larva, abilLink=193, abilCmdIndex selects unit) ---
    private static final int ABIL_LARVA       = 193;
    // Queen is trained from Hatchery via abilLink=184 abilCmdIndex=1; other indices are macro (inject)
    private static final int ABIL_HATCHERY    = 184;

    // --- Terran train (AI Arena build 75689) ---
    // Derived from TerranDiscoveryTest: no-target Cmd events cross-referenced across
    // Nothing_4720935 (18m), Tyckles_4721034 (15m), Starlight_4721165 (6m) Terran-wins PvT.
    // Other abilLinks (157, 158, 161) have insufficient cross-replay evidence — logged as unknown.
    private static final int ABIL_COMMAND_CENTER = 155; // idx=0 only → SCV
    private static final int ABIL_BARRACKS       = 159; // idx=0 → Marine, idx=3 → Marauder

    private static final Map<Integer, UnitType> BARRACKS_UNITS = Map.of(
            0, UnitType.MARINE,
            3, UnitType.MARAUDER
    );

    // Gateway abilCmdIndex → UnitType (from discovery: idx1=Zealot most common, idx0=Stalker)
    private static final Map<Integer, UnitType> GATEWAY_UNITS = Map.of(
            0, UnitType.STALKER,
            1, UnitType.ZEALOT,
            5, UnitType.ADEPT
    );

    // Robotics abilCmdIndex → UnitType
    private static final Map<Integer, UnitType> ROBOTICS_UNITS = Map.of(
            0, UnitType.IMMORTAL,
            1, UnitType.OBSERVER,
            2, UnitType.COLOSSUS
    );

    // Stargate abilCmdIndex → UnitType
    private static final Map<Integer, UnitType> STARGATE_UNITS = Map.of(
            0, UnitType.PHOENIX,
            1, UnitType.VOID_RAY,
            2, UnitType.ORACLE
    );

    // Zerg larva abilCmdIndex → UnitType
    private static final Map<Integer, UnitType> LARVA_UNITS = Map.of(
            0, UnitType.DRONE,
            1, UnitType.ZERGLING,
            2, UnitType.ROACH,
            3, UnitType.HYDRALISK,
            4, UnitType.MUTALISK,
            5, UnitType.CORRUPTOR,
            6, UnitType.ULTRALISK,
            7, UnitType.INFESTOR,
            8, UnitType.SWARM_HOST,
            9, UnitType.VIPER
    );

    private final int userId;  // 0-indexed game event userId = (playerId - 1)
    private final SelectionState selection = new SelectionState();

    public AbilityMapping(int playerId) {
        this.userId = playerId - 1;
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

        return switch (abilLink) {
            case ABIL_SMART, ABIL_ATTACK_MOVE, ABIL_WARPGATE ->
                    moveOrders(event, loop);

            case ABIL_NEXUS ->
                    trainIntent(loop, UnitType.PROBE);

            case ABIL_GATEWAY -> {
                UnitType unit = GATEWAY_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_ROBOTICS -> {
                UnitType unit = ROBOTICS_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_STARGATE -> {
                UnitType unit = STARGATE_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_LARVA -> {
                UnitType unit = LARVA_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            case ABIL_HATCHERY ->
                // abilCmdIndex=1 = Train Queen; other indices are macro (inject, etc.) — skip
                    idx == 1 ? trainIntent(loop, UnitType.QUEEN) : List.of();

            case ABIL_COMMAND_CENTER ->
                    // idx=0 = Train SCV; other indices are Orbital abilities (call-down MULE, scan) — skip
                    idx == 0 ? trainIntent(loop, UnitType.SCV) : unknown(abilLink, idx);

            case ABIL_BARRACKS -> {
                UnitType unit = BARRACKS_UNITS.get(idx);
                yield unit != null ? trainIntent(loop, unit) : unknown(abilLink, idx);
            }

            default -> unknown(abilLink, idx);
        };
    }

    private List<ReplayCommand> trainIntent(long loop, UnitType unitType) {
        String buildingTag = selection.first();
        return List.of(new ReplayCommand.IntentCommand(
                new TimedIntent(loop, new TrainIntent(buildingTag, unitType))));
    }

    private List<ReplayCommand> moveOrders(CmdEvent event, long loop) {
        var tp = event.getTargetPoint();
        var tu = event.getTargetUnit();
        List<ReplayCommand> orders = new ArrayList<>(selection.size());
        for (String tag : selection.snapshot()) {
            if (tp != null) {
                float x = tp.getXFloat();
                float y = tp.getYFloat();
                if (x >= 0 && x <= 256 && y >= 0 && y <= 256) {
                    orders.add(new ReplayCommand.Movement(
                            new UnitOrder(tag, loop, new Point2d(x, y), null)));
                }
            } else if (tu != null && tu.getTag() != null) {
                orders.add(new ReplayCommand.Movement(
                        new UnitOrder(tag, loop, null, GameEventStream.decodeTag(tu.getTag()))));
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
