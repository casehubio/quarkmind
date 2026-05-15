package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
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
 * Terran coverage pending issue #140.
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
    private List<String> currentSelection = List.of();

    public AbilityMapping(int playerId) {
        this.userId = playerId - 1;
    }

    public void onSelection(SelectionDeltaEvent event) {
        if (event.getUserId() != userId) return;
        var delta = event.getDelta();
        if (delta == null || delta.getAddUnitTags() == null) {
            currentSelection = List.of();
            return;
        }
        currentSelection = java.util.Arrays.stream(delta.getAddUnitTags())
                .filter(Objects::nonNull)
                .map(GameEventStream::decodeTag)
                .toList();
    }

    public List<ReplayCommand> process(CmdEvent event) {
        if (event.getUserId() != userId) return List.of();
        if (currentSelection.isEmpty()) return List.of();
        Integer abilLink = event.getAbilLink();
        if (abilLink == null) return List.of();
        int idx = Objects.requireNonNullElse(event.getAbilCmdIndex(), 0);
        return dispatch(abilLink, idx, event);
    }

    public void reset() {
        currentSelection = List.of();
    }

    /** Package-private — used by AbilityMappingTest to prime selection without replay parsing. */
    void setSelectionForTest(int forUserId, List<String> tags) {
        if (forUserId == this.userId) this.currentSelection = List.copyOf(tags);
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

            default -> unknown(abilLink, idx);
        };
    }

    private List<ReplayCommand> trainIntent(long loop, UnitType unitType) {
        String buildingTag = currentSelection.get(0);
        return List.of(new ReplayCommand.IntentCommand(
                new TimedIntent(loop, new TrainIntent(buildingTag, unitType))));
    }

    private List<ReplayCommand> moveOrders(CmdEvent event, long loop) {
        var tp = event.getTargetPoint();
        var tu = event.getTargetUnit();
        List<ReplayCommand> orders = new ArrayList<>(currentSelection.size());
        for (String tag : currentSelection) {
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
}
