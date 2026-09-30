package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.sllauncher.util.Pair;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
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
    static final int ZEALOT_ABIL_IDX  = 0;
    static final int STALKER_ABIL     = 172;
    static final int STALKER_ABIL_IDX = 1;

    // Terran constants — AI Arena build 75689 (derived from TerranDiscoveryTest 2026-05-29)
    static final int TERRAN_CC_ABIL        = 155; // Command Center → SCV (idx=0 only)
    static final int TERRAN_BARRACKS_ABIL  = 159; // Barracks → Marine (idx=0), Marauder (idx=3)
    static final int TERRAN_MARINE_IDX     = 0;
    static final int TERRAN_MARAUDER_IDX   = 3;
    // --- Human replay building placement (from AbilityDiscoveryCalibrationTest) ---
    static final int ABIL_SCV_BUILD        = 129;
    static final int ABIL_PROBE_BUILD      = 170;  // same value as ABIL_WARPGATE in bot mode
    static final int ABIL_DRONE_BUILD      = 183;
    static final int ABIL_BARRACKS_ADDON   = 147;
    static final int ABIL_FACTORY_ADDON    = 149;
    static final int ABIL_STARPORT_ADDON   = 151;
    static final int ABIL_WARPGATE_WARPIN  = 214;
    static final int ABIL_ARCHON_MERGE     = 267;
    static final int ABIL_BANELING_MORPH   = 73;
    static final int ABIL_RAVAGER_MORPH    = 309;
    static final int ABIL_BROODLORD_MORPH  = 194;
    static final int ABIL_LURKER_MORPH     = 522;
    static final int ABIL_OVERSEER_MORPH   = 221;

    // Zerg constants
    static final int ABIL_LARVA            = 193;
    static final int ABIL_HATCHERY         = 184;
    static final int ABIL_LAIR             = 186;


    AbilityMapping mapping;
    AbilityMapping humanMapping;


    @BeforeEach
    void setUp() {
        mapping      = new AbilityMapping(1);
        humanMapping = new AbilityMapping(1, true);
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
                && o.targetPos().x() == 90f && o.targetPos().y() == 110f);
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
// --- Human replay mode tests ---

    @Test
    void humanMode_scvBuildSupplyDepot_producesBuildCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-scv-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_SCV_BUILD, 0, 500, new float[]{40f, 60f}, null, 1));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.loop()).isEqualTo(500);
        assertThat(bc.buildingName()).isEqualTo("SupplyDepot");
        assertThat(bc.position().x()).isEqualTo(80f);
        assertThat(bc.position().y()).isEqualTo(120f);
    }

    @Test
    void humanMode_scvBuildBarracks_producesBuildCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-scv-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_SCV_BUILD, 0, 600, new float[]{30f, 40f}, null, 3));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("Barracks");
    }

    @Test
    void humanMode_probeBuildPylon_producesBuildCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-probe-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_PROBE_BUILD, 0, 700, new float[]{50f, 50f}, null, 1));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("Pylon");
    }

    @Test
    void humanMode_probeBuildGateway_producesBuildCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-probe-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_PROBE_BUILD, 0, 800, new float[]{55f, 45f}, null, 3));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("Gateway");
    }

    @Test
    void humanMode_droneBuildHatchery_producesBuildCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-drone-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_DRONE_BUILD, 0, 900, new float[]{70f, 30f}, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("Hatchery");
    }

    @Test
    void humanMode_droneBuildSpawningPool_producesBuildCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-drone-2"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_DRONE_BUILD, 0, 1000, new float[]{65f, 35f}, null, 3));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("SpawningPool");
    }

    @Test
    void humanMode_barracksAddonTechLab_producesBuildCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-bx-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_BARRACKS_ADDON, 0, 1100, new float[]{40f, 50f}, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("BarracksTechLab");
    }

    @Test
    void humanMode_warpGateWarpIn_producesStalkerTrainIntent() {
        humanMapping.setSelectionForTest(0, List.of("r-wg-0"));
        // Prime: first warp-in emits the upgrade — consume it
        humanMapping.process(fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 1100, new float[]{40f, 50f}, null, 0));

        humanMapping.setSelectionForTest(0, List.of("r-wg-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 1200, new float[]{45f, 55f}, null, 1));
        assertThat(result).hasSize(1);
        ReplayCommand.IntentCommand ic = (ReplayCommand.IntentCommand) result.get(0);
        TrainIntent                 t  = (TrainIntent) ic.intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.STALKER);
    }

    @Test
    void humanMode_warpGateWarpIn_producesAdeptTrainIntent() {
        humanMapping.setSelectionForTest(0, List.of("r-wg-0"));
        // Prime: first warp-in emits the upgrade — consume it
        humanMapping.process(fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 1200, new float[]{40f, 50f}, null, 0));

        humanMapping.setSelectionForTest(0, List.of("r-wg-2"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 1300, new float[]{50f, 60f}, null, 6));
        assertThat(result).hasSize(1);
        ReplayCommand.IntentCommand ic = (ReplayCommand.IntentCommand) result.get(0);
        TrainIntent                 t  = (TrainIntent) ic.intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.ADEPT);
    }

    @Test
    void humanMode_firstWarpIn_emitsWarpGateResearchUpgrade() {
        humanMapping.setSelectionForTest(0, List.of("r-wg-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 4000, new float[]{45f, 55f}, null, 1));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(ReplayCommand.UpgradeCommand.class);
        ReplayCommand.UpgradeCommand uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("WarpGateResearch");
        long expectedStart = 4000 - SC2Data.upgradeTimeInLoops(UpgradeType.WARP_GATE_RESEARCH);
        assertThat(uc.loop()).isEqualTo(expectedStart);

        assertThat(result.get(1)).isInstanceOf(ReplayCommand.IntentCommand.class);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(1)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.STALKER);
    }

    @Test
    void humanMode_secondWarpIn_doesNotEmitUpgradeAgain() {
        humanMapping.setSelectionForTest(0, List.of("r-wg-1"));
        humanMapping.process(fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 4000, new float[]{45f, 55f}, null, 1));

        humanMapping.setSelectionForTest(0, List.of("r-wg-2"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 4500, new float[]{50f, 60f}, null, 0));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(ReplayCommand.IntentCommand.class);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.ZEALOT);
    }


    @Test
    void humanMode_warpInWithEmptySelection_stillProducesTrainIntent() {
        // No selection primed — simulates rapid-fire warp-in or "Select All WarpGates" not tracked
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 5000, new float[]{45f, 55f}, null, 1));
        assertThat(result).isNotEmpty();
        // Find the IntentCommand (first warp-in also emits UpgradeCommand)
        var intents = result.stream()
                            .filter(r -> r instanceof ReplayCommand.IntentCommand)
                            .toList();
        assertThat(intents).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) intents.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.STALKER);
        assertThat(t.buildingTag()).isNull();
    }

    @Test
    void humanMode_buildWithEmptySelection_stillProducesBuildCommand() {
        // No selection primed — human build commands are self-identifying via abilLink
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_SCV_BUILD, 0, 5000, new float[]{40f, 60f}, null, 1));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("SupplyDepot");
    }

    @Test
    void humanMode_warpInWithEmptySelection_tracksUnitType() {
        // First warp-in primes warpGateResearchEmitted
        humanMapping.setSelectionForTest(0, List.of("r-wg-0"));
        humanMapping.process(fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 1100, new float[]{40f, 50f}, null, 0));

        // Second warp-in with empty selection (rapid-fire via "Select All WarpGates")
        humanMapping.setSelectionForTest(0, List.of());
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_WARPGATE_WARPIN, 0, 5000, new float[]{45f, 55f}, null, 1));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.STALKER);
        assertThat(t.buildingTag()).isNull();
    }


    @Test
    void humanMode_archonMerge_producesMorphCommand() {
        humanMapping.setSelectionForTest(0, List.of("r-ht-1", "r-ht-2"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_ARCHON_MERGE, 0, 1400, null, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.MorphCommand mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.loop()).isEqualTo(1400);
        assertThat(mc.sourceName()).isEqualTo("HighTemplar");
        assertThat(mc.targetName()).isEqualTo("Archon");
    }

    @Test
    void humanMode_archonMerge_darkTemplarSelection_producesDTSource() {
        var protossMapping = new AbilityMapping(1, true, Race.PROTOSS);
        // Select 2 Dark Templars via selection delta with unitLink=76 (DarkTemplar)
        int dtUnitLink = 76;
        protossMapping.onSelection(selectionEvent(0, null, null,
                                                  new int[][]{{dtUnitLink, 2}},
                                                  new Integer[]{(1 << 18) | 1, (2 << 18) | 1}));
        List<ReplayCommand> result = protossMapping.process(
                fakeCmdEvent(ABIL_ARCHON_MERGE, 0, 1400, null, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.MorphCommand mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("DarkTemplar");
        assertThat(mc.targetName()).isEqualTo("Archon");
    }

    @Test
    void humanMode_archonMerge_highTemplarSelection_producesHTSource() {
        var protossMapping = new AbilityMapping(1, true, Race.PROTOSS);
        // Select 2 High Templars via selection delta with unitLink=75 (HighTemplar)
        int htUnitLink = 75;
        protossMapping.onSelection(selectionEvent(0, null, null,
                                                  new int[][]{{htUnitLink, 2}},
                                                  new Integer[]{(3 << 18) | 1, (4 << 18) | 1}));
        List<ReplayCommand> result = protossMapping.process(
                fakeCmdEvent(ABIL_ARCHON_MERGE, 0, 1500, null, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.MorphCommand mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("HighTemplar");
        assertThat(mc.targetName()).isEqualTo("Archon");
    }

    @Test
    void humanMode_archonMerge_noUnitLinkData_defaultsToHighTemplar() {
        // setSelectionForTest doesn't set unitLinks — should default to HighTemplar
        humanMapping.setSelectionForTest(0, List.of("r-ht-1", "r-ht-2"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_ARCHON_MERGE, 0, 1600, null, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.MorphCommand mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("HighTemplar");
    }


    @Test
    void humanMode_banelingMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-z-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_BANELING_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Zergling");
        assertThat(mc.targetName()).isEqualTo("Baneling");
    }

    @Test
    void humanMode_ravagerMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-r-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_RAVAGER_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Roach");
        assertThat(mc.targetName()).isEqualTo("Ravager");
    }

    @Test
    void humanMode_broodlordMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-c-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_BROODLORD_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Corruptor");
        assertThat(mc.targetName()).isEqualTo("BroodLord");
    }

    @Test
    void humanMode_lurkerMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-h-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_LURKER_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Hydralisk");
        assertThat(mc.targetName()).isEqualTo("Lurker");
    }

    @Test
    void humanMode_overseerMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-o-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_OVERSEER_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Overlord");
        assertThat(mc.targetName()).isEqualTo("Overseer");
    }


    @Test
    void humanMode_unknownBuildIdx_returnsEmpty() {
        humanMapping.setSelectionForTest(0, List.of("r-scv-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_SCV_BUILD, 0, 1500, new float[]{40f, 60f}, null, 99));
        assertThat(result).isEmpty();
    }

    @Test
    void humanMode_buildWithoutTargetPoint_returnsEmpty() {
        humanMapping.setSelectionForTest(0, List.of("r-scv-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_SCV_BUILD, 0, 1600, null, null, 1));
        assertThat(result).isEmpty();
    }

    @Test
    void humanMode_existingTrainStillWorks() {
        humanMapping.setSelectionForTest(0, List.of("r-nexus-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(PROBE_ABIL, 0, 1700, null, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.IntentCommand ic = (ReplayCommand.IntentCommand) result.get(0);
        TrainIntent                 t  = (TrainIntent) ic.intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.PROBE);
    }

    @Test
    void botMode_abilLink170_producesMovement() {
        mapping.setSelectionForTest(0, List.of("r-wg-1"));
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(170, 0, 1800, new float[]{45f, 55f}, null, 0));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(ReplayCommand.Movement.class);
    }

    @Test
    void larvaTrain_withSelection_producesDrone() {
        humanMapping.setSelectionForTest(0, List.of("r-hatch-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_LARVA, 0, 2000, null, null, 0));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.DRONE);
        assertThat(t.buildingTag()).isEqualTo("r-hatch-1");
    }

    @Test
    void larvaTrain_withSelection_producesZergling() {
        humanMapping.setSelectionForTest(0, List.of("r-hatch-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_LARVA, 0, 2100, null, null, 1));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.ZERGLING);
    }

    @Test
    void hatcheryQueen_withSelection_producesQueen() {
        humanMapping.setSelectionForTest(0, List.of("r-hatch-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_HATCHERY, 0, 2400, null, null, 1));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.QUEEN);
    }

    @Test
    void lairQueen_withSelection_producesQueen() {
        humanMapping.setSelectionForTest(0, List.of("r-lair-1"));
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_LAIR, 0, 2500, null, null, 0));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.QUEEN);
    }

    @Test
    void larvaTrain_emptySelection_blockedInBotMode() {
        List<ReplayCommand> result = mapping.process(
                fakeCmdEvent(ABIL_LARVA, 0, 2600, null, null, 0));
        assertThat(result).isEmpty();
    }

    @Test
    void humanMode_larvaTrain_emptySelection_stillProduces() {
        List<ReplayCommand> result = humanMapping.process(
                fakeCmdEvent(ABIL_LARVA, 0, 2600, null, null, 0));
        assertThat(result).hasSize(1);
        TrainIntent t = (TrainIntent) ((ReplayCommand.IntentCommand) result.get(0)).intent().intent();
        assertThat(t.unitType()).isEqualTo(UnitType.DRONE);
        assertThat(t.buildingTag()).isNull();
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

    @SuppressWarnings("unchecked")
    private SelectionDeltaEvent selectionEvent(int userId, String removeVariant,
                                               Object removeValue, int[][] subgroups,
                                               Integer[] addUnitTags) {
        Pair<String, Object> removeMask = removeVariant != null
                                          ? new Pair<>(removeVariant, removeValue) : null;
        Map<String, Object> deltaMap = new HashMap<>();
        deltaMap.put("removeMask", removeMask);
        deltaMap.put("addUnitTags", addUnitTags != null ? addUnitTags : new Integer[0]);
        deltaMap.put("subgroupIndex", 0);
        if (subgroups != null) {
            Map<String, Object>[] sgMaps = new Map[subgroups.length];
            for (int i = 0; i < subgroups.length; i++) {
                sgMaps[i] = Map.of("unitLink", subgroups[i][0], "count", subgroups[i][1],
                                   "subgroupPriority", 0, "intraSubgroupPriority", 0);
            }
            deltaMap.put("addSubgroups", sgMaps);
        } else {
            deltaMap.put("addSubgroups", new Map[0]);
        }
        Map<String, Object> struct = new HashMap<>();
        struct.put("delta", deltaMap);
        return new SelectionDeltaEvent(struct, 0, "SelectionDeltaEvent", 0, userId, 16561);
    }

}
