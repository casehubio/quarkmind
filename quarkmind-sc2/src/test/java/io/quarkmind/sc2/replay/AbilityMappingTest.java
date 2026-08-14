package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.sllauncher.util.Pair;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AbilityMappingTest {

    // From Task 2 discovery output
    static final int MOVE_ABIL        = 42;
    static final int PROBE_ABIL       = 175;
    static final int ZEALOT_ABIL      = 172;
    static final int ZEALOT_ABIL_IDX  = 1;
    static final int STALKER_ABIL     = 172;
    static final int STALKER_ABIL_IDX = 0;

    // Terran constants — AI Arena build 75689 (derived from TerranDiscoveryTest 2026-05-29)
    static final int TERRAN_CC_ABIL        = 155; // Command Center → SCV (idx=0 only)
    static final int TERRAN_BARRACKS_ABIL  = 159; // Barracks → Marine (idx=0), Marauder (idx=3)
    static final int TERRAN_MARINE_IDX     = 0;
    static final int TERRAN_MARAUDER_IDX   = 3;

    AbilityMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = new AbilityMapping(1); // player 1 = userId 0
    }

    @Test
    void unknownAbilLinkReturnsEmptyList() {
        mapping.setSelectionForTest(0, List.of("r-1-1"));
        List<ReplayCommand> result = mapping.process(fakeCmdEvent(99999, 0, 100, null, null, 0));
        assertThat(result).isEmpty();
    }

    @Test
    void noSelectionReturnsEmptyList() {
        // No selection primed
        List<ReplayCommand> result = mapping.process(fakeCmdEvent(MOVE_ABIL, 0, 100, new float[]{50f, 60f}, null, 0));
        assertThat(result).isEmpty();
    }

    @Test
    void moveCommandProducesOneOrderPerSelectedUnit() {
        mapping.setSelectionForTest(0, List.of("r-1-1", "r-2-1", "r-3-1"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(MOVE_ABIL, 0, 200, new float[]{45f, 55f}, null, 0));
        assertThat(result).hasSize(3);
        assertThat(result).allMatch(r -> r instanceof ReplayCommand.Movement);
        List<UnitOrder> orders = result.stream()
                .map(r -> ((ReplayCommand.Movement) r).order()).toList();
        assertThat(orders).allMatch(o -> o.loop() == 200);
        assertThat(orders).allMatch(o -> o.targetPos() != null
                && o.targetPos().x() == 45f && o.targetPos().y() == 55f);
        assertThat(orders.stream().map(UnitOrder::unitTag).toList())
                .containsExactlyInAnyOrder("r-1-1", "r-2-1", "r-3-1");
    }

    @Test
    void trainProbeProducesSingleTrainIntent() {
        mapping.setSelectionForTest(0, List.of("r-10-1")); // nexus tag
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(PROBE_ABIL, 0, 300, null, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.IntentCommand ic = (ReplayCommand.IntentCommand) result.get(0);
        assertThat(ic.intent().loop()).isEqualTo(300);
        TrainIntent t = (TrainIntent) ic.intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.PROBE);
        assertThat(t.buildingTag()).isEqualTo("r-10-1");
    }

    @Test
    void trainZealotProducesSingleTrainIntent() {
        mapping.setSelectionForTest(0, List.of("r-20-1"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(ZEALOT_ABIL, 0, 400, null, null, ZEALOT_ABIL_IDX));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.ZEALOT);
    }

    @Test
    void trainStalkerProducesSingleTrainIntent() {
        mapping.setSelectionForTest(0, List.of("r-21-1"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(STALKER_ABIL, 0, 500, null, null, STALKER_ABIL_IDX));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.STALKER);
    }

    @Test
    void resetClearsSelection() {
        mapping.setSelectionForTest(0, List.of("r-1-1"));
        mapping.reset();
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(MOVE_ABIL, 0, 100, new float[]{10f, 10f}, null, 0));
        assertThat(result).isEmpty();
    }

    @Test
    void otherPlayersCommandsIgnored() {
        mapping.setSelectionForTest(0, List.of("r-1-1"));
        // userId=1 is player 2, mapping is for player 1 (userId=0)
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(MOVE_ABIL, 1, 100, new float[]{10f, 10f}, null, 0));
        assertThat(result).isEmpty();
    }

    @Test
    void trainScvProducesSingleTrainIntent() {
        mapping.setSelectionForTest(0, List.of("r-cc-1"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(TERRAN_CC_ABIL, 0, 500, null, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.IntentCommand ic = (ReplayCommand.IntentCommand) result.get(0);
        assertThat(ic.intent().loop()).isEqualTo(500);
        TrainIntent t = (TrainIntent) ic.intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.SCV);
        assertThat(t.buildingTag()).isEqualTo("r-cc-1");
    }

    @Test
    void unknownCommandCenterIndexReturnsEmpty() {
        mapping.setSelectionForTest(0, List.of("r-cc-1"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(TERRAN_CC_ABIL, 0, 900, null, null, 99));
        assertThat(result).isEmpty();
    }

    @Test
    void trainMarineProducesSingleTrainIntent() {
        mapping.setSelectionForTest(0, List.of("r-bx-1"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(TERRAN_BARRACKS_ABIL, 0, 600, null, null, TERRAN_MARINE_IDX));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.MARINE);
    }

    @Test
    void trainMarauderProducesSingleTrainIntent() {
        mapping.setSelectionForTest(0, List.of("r-bx-2"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(TERRAN_BARRACKS_ABIL, 0, 700, null, null, TERRAN_MARAUDER_IDX));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.MARAUDER);
    }

    @Test
    void unknownBarracksIndexReturnsEmpty() {
        mapping.setSelectionForTest(0, List.of("r-bx-3"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(TERRAN_BARRACKS_ABIL, 0, 800, null, null, 99));
        assertThat(result).isEmpty();
    }

    /**
     * Construct a minimal CmdEvent via its public constructor.
     * Fixed-point encoding: getXFloat() = rawInt / 8192.0f (verified from TargetPoint.java source).
     */
    private CmdEvent fakeCmdEvent(int abilLink, int userId, long loop,
                                  float[] targetPoint, Integer targetUnitRawTag, int abilCmdIndex) {
        Map<String, Object> struct = new HashMap<>();
        Map<String, Object> abil = new HashMap<>();
        abil.put("abilLink", abilLink);
        abil.put("abilCmdIndex", abilCmdIndex);
        struct.put("abil", abil);
        struct.put("cmdFlags", 0);
        if (targetPoint != null) {
            Map<String, Object> tp = new HashMap<>();
            // SC2 fixed-point: getXFloat() = rawInt / 8192.0f
            tp.put("x", (int) (targetPoint[0] * 8192));
            tp.put("y", (int) (targetPoint[1] * 8192));
            tp.put("z", 0);
            struct.put("data", new Pair<>("TargetPoint", tp));
        } else if (targetUnitRawTag != null) {
            Map<String, Object> tu = new HashMap<>();
            tu.put("tag", targetUnitRawTag);
            tu.put("targetUnitFlags", 0);
            tu.put("timer", 0);
            tu.put("snapshotUnitLink", 0);
            tu.put("snapshotPlayerId", 0);
            struct.put("data", new Pair<>("TargetUnit", tu));
        }
        return new CmdEvent(struct, 27, "BasicCommandEvent", (int) loop, userId, 99999, null);
    }
}
