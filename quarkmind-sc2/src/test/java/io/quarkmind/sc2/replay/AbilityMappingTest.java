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
    static final int ABIL_CC_MORPH         = 120;
    static final int ABIL_CREEP_TUMOR_QUEEN = 260;
    static final int ABIL_CREEP_TUMOR_SPREAD = 265;
    static final int ABIL_NYDUS_SPAWN = 268;
    static final int ABIL_ORACLE_STASIS_WARD = 603;
    static final int ABIL_LAIR_MORPH          = 249;
    static final int ABIL_HIVE_MORPH          = 250;
    static final int ABIL_GREATER_SPIRE_MORPH = 252;

    // Tournament-era morph abilLinks (post-4.9.x patches, +2 shift)
    static final int ABIL_BANELING_MORPH_TOURNAMENT   = 730;
    static final int ABIL_RAVAGER_MORPH_TOURNAMENT    = 311;
    static final int ABIL_BROODLORD_MORPH_TOURNAMENT  = 196;
    static final int ABIL_LURKER_MORPH_TOURNAMENT     = 524;
    static final int ABIL_OVERSEER_MORPH_TOURNAMENT   = 223;

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
    void humanMode_ccMorphOrbitalCommand_producesMorphCommand() {
        var terranMapping = new AbilityMapping(1, true, Race.TERRAN);
        terranMapping.setSelectionForTest(0, List.of("r-cc-1"));
        var result = terranMapping.process(fakeCmdEvent(ABIL_CC_MORPH, 0, 2000, null, null, 1));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("CommandCenter");
        assertThat(mc.targetName()).isEqualTo("OrbitalCommand");
    }

    @Test
    void humanMode_ccMorphPlanetaryFortress_producesMorphCommand() {
        var terranMapping = new AbilityMapping(1, true, Race.TERRAN);
        terranMapping.setSelectionForTest(0, List.of("r-cc-1"));
        var result = terranMapping.process(fakeCmdEvent(ABIL_CC_MORPH, 0, 2100, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("CommandCenter");
        assertThat(mc.targetName()).isEqualTo("PlanetaryFortress");
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
    void humanMode_queenCreepTumor_producesBuildCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-queen-1"));
        List<ReplayCommand> result = zergMapping.process(
                fakeCmdEvent(ABIL_CREEP_TUMOR_QUEEN, 0, 3000, new float[]{25f, 35f}, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("CreepTumorQueen");
        assertThat(bc.loop()).isEqualTo(3000);
    }

    @Test
    void humanMode_creepTumorSpread_producesBuildCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-tumor-1"));
        List<ReplayCommand> result = zergMapping.process(
                fakeCmdEvent(ABIL_CREEP_TUMOR_SPREAD, 0, 4000, new float[]{30f, 40f}, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("CreepTumor");
    }

    @Test
    void humanMode_nydusSpawn_producesBuildCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-nydus-1"));
        List<ReplayCommand> result = zergMapping.process(
                fakeCmdEvent(ABIL_NYDUS_SPAWN, 0, 5000, new float[]{50f, 50f}, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("NydusCanal");
    }

    @Test
    void humanMode_oracleStasisWard_producesBuildCommand() {
        var protossMapping = new AbilityMapping(1, true, Race.PROTOSS);
        protossMapping.setSelectionForTest(0, List.of("r-oracle-1"));
        List<ReplayCommand> result = protossMapping.process(
                fakeCmdEvent(ABIL_ORACLE_STASIS_WARD, 0, 6000, new float[]{45f, 55f}, null, 0));
        assertThat(result).hasSize(1);
        ReplayCommand.BuildCommand bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("OracleStasisTrap");
    }

    @Test
    void humanMode_creepTumorQueen_wrongRace_returnsEmpty() {
        var protossMapping = new AbilityMapping(1, true, Race.PROTOSS);
        protossMapping.setSelectionForTest(0, List.of("r-1"));
        List<ReplayCommand> result = protossMapping.process(
                fakeCmdEvent(ABIL_CREEP_TUMOR_QUEEN, 0, 3000, new float[]{25f, 35f}, null, 0));
        assertThat(result).isEmpty();
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


    @Test
    void humanMode_lairMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-h-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_LAIR_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Hatchery");
        assertThat(mc.targetName()).isEqualTo("Lair");
    }

    @Test
    void humanMode_hiveMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-l-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_HIVE_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Lair");
        assertThat(mc.targetName()).isEqualTo("Hive");
    }

    @Test
    void humanMode_greaterSpireMorph_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-s-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_GREATER_SPIRE_MORPH, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Spire");
        assertThat(mc.targetName()).isEqualTo("GreaterSpire");
    }

    @Test
    void humanMode_lairMorph_nonZerg_returnsNull() {
        var terranMapping = new AbilityMapping(1, true, Race.TERRAN);
        var result = terranMapping.process(fakeCmdEvent(ABIL_LAIR_MORPH, 0, 1000, null, null, 0));
        assertThat(result).isEmpty();
    }

    // --- Upgrade research tests (from correlateUpgradesWithUnmappedAbilLinks, 118 oracle replays) ---

    // --- Engineering Bay (abilLink=162) ---

    @Test
    void humanMode_engBay_infantryWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 5000, null, null, 2));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(ReplayCommand.UpgradeCommand.class);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranInfantryWeaponsLevel1");
        assertThat(uc.loop()).isEqualTo(5000);
    }

    @Test
    void humanMode_engBay_buildingArmor_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 6000, null, null, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranBuildingArmor");
    }

    @Test
    void humanMode_engBay_infantryWeaponsLevel2_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 7000, null, null, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranInfantryWeaponsLevel2");
    }

    @Test
    void humanMode_engBay_infantryArmorsLevel1_atIdx6_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 7000, null, null, 6));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranInfantryArmorsLevel1");
    }

    @Test
    void humanMode_engBay_infantryArmorsLevel2_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 7000, null, null, 7));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranInfantryArmorsLevel2");
    }

    @Test
    void humanMode_engBay_infantryWeaponsLevel3_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 8000, null, null, 4));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranInfantryWeaponsLevel3");
    }

    @Test
    void humanMode_engBay_wrongRace_returnsEmpty() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(162, 0, 5000, null, null, 2));
        assertThat(result).isEmpty();
    }

    // --- BarracksTechLab (abilLink=165 Stimpack, abilLink=152 ConcussiveShells) ---

    @Test
    void humanMode_barracksTechLab_stimpack_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(165, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("Stimpack");
    }

    @Test
    void humanMode_barracksTechLab_concussiveShells_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(152, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("PunisherGrenades");
    }

    // --- StarportTechLab (abilLink=167) ---

    @Test
    void humanMode_starportTechLab_bansheeCloak_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(167, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("BansheeCloak");
    }

    @Test
    void humanMode_starportTechLab_bansheeSpeed_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(167, 0, 5000, null, null, 9));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("BansheeSpeed");
    }

    @Test
    void humanMode_starportTechLab_liberatorRange_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(167, 0, 5000, null, null, 15));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("LiberatorAGRangeUpgrade");
    }

    // --- FactoryTechLab (abilLink=166) ---

    @Test
    void humanMode_factoryTechLab_highCapacityBarrels_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(166, 0, 5000, null, null, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("HighCapacityBarrels");
    }

    @Test
    void humanMode_factoryTechLab_smartServos_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(166, 0, 5000, null, null, 6));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("SmartServos");
    }

    // --- Armory (abilLink=169) ---

    @Test
    void humanMode_armory_vehicleWeaponsLevel2_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(169, 0, 5000, null, null, 6));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranVehicleWeaponsLevel2");
    }

    @Test
    void humanMode_armory_vehicleAndShipArmorsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(169, 0, 5000, null, null, 14));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranVehicleAndShipArmorsLevel1");
    }

    @Test
    void humanMode_armory_shipWeaponsLevel3_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(169, 0, 5000, null, null, 13));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranShipWeaponsLevel3");
    }

    // --- FusionCore (abilLink=235) ---

    @Test
    void humanMode_fusionCore_battlecruiserSpecializations_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(235, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("BattlecruiserEnableSpecializations");
    }

    // --- EvolutionChamber (abilLink=185) ---

    @Test
    void humanMode_evoChamber_meleeWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(185, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergMeleeWeaponsLevel1");
    }

    @Test
    void humanMode_evoChamber_groundArmorsLevel2_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(185, 0, 5000, null, null, 4));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergGroundArmorsLevel2");
    }

    @Test
    void humanMode_evoChamber_wrongRace_returnsEmpty() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(185, 0, 5000, null, null, 0));
        assertThat(result).isEmpty();
    }

    // --- Spire (abilLink=192) ---

    @Test
    void humanMode_spire_flyerWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(192, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergFlyerWeaponsLevel1");
    }

    @Test
    void humanMode_spire_flyerArmorsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(192, 0, 5000, null, null, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergFlyerArmorsLevel1");
    }

    // --- BanelingNest (abilLink=224) ---

    @Test
    void humanMode_banelingNest_centrifugalHooks_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(224, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("CentrificalHooks");
    }

    // --- RoachWarren (abilLink=107) ---

    @Test
    void humanMode_roachWarren_glialReconstitution_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(107, 0, 5000, null, null, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("GlialReconstitution");
    }

    // --- HydraliskDen (abilLink=262 GroovedSpines, abilLink=310 MuscularAugments) ---

    @Test
    void humanMode_hydraliskDen_groovedSpines_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(262, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("EvolveGroovedSpines");
    }

    @Test
    void humanMode_hydraliskDen_muscularAugments_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(310, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("EvolveMuscularAugments");
    }

    // --- UltraliskCavern (abilLink=117) ---

    @Test
    void humanMode_ultraliskCavern_chitinousPlating_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(117, 0, 5000, null, null, 2));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ChitinousPlating");
    }

    // --- Forge (abilLink=180) ---

    @Test
    void humanMode_forge_groundWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(180, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ProtossGroundWeaponsLevel1");
    }

    @Test
    void humanMode_forge_shieldsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(180, 0, 5000, null, null, 6));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ProtossShieldsLevel1");
    }

    @Test
    void humanMode_forge_wrongRace_returnsEmpty() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(180, 0, 5000, null, null, 0));
        assertThat(result).isEmpty();
    }

    // --- CyberneticsCore (abilLink=236) ---

    @Test
    void humanMode_cyberneticsCore_warpGateResearch_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(236, 0, 5000, null, null, 6));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("WarpGateResearch");
    }

    // --- TwilightCouncil (abilLink=547) ---

    @Test
    void humanMode_twilightCouncil_charge_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(547, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("Charge");
    }

    // --- TemplarArchive (abilLink=182) ---

    @Test
    void humanMode_templarArchive_psiStorm_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(182, 0, 5000, null, null, 4));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("PsiStormTech");
    }

    // --- Gap-filling: pattern-inferred and frequency-confirmed mappings ---

    @Test
    void humanMode_engBay_hiSecAutoTracking_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("HiSecAutoTracking");
    }

    @Test
    void humanMode_armory_vehicleWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(169, 0, 5000, null, null, 5));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranVehicleWeaponsLevel1");
    }

    @Test
    void humanMode_armory_shipWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(169, 0, 5000, null, null, 11));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranShipWeaponsLevel1");
    }

    @Test
    void humanMode_cycloneLockOn_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(148, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("CycloneLockOnDamageUpgrade");
    }

    @Test
    void humanMode_roachWarren_tunnelingClaws_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(107, 0, 5000, null, null, 2));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TunnelingClaws");
    }

    @Test
    void humanMode_spire_flyerWeaponsLevel3_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(192, 0, 5000, null, null, 2));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergFlyerWeaponsLevel3");
    }

    @Test
    void humanMode_spire_flyerArmorsLevel3_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(192, 0, 5000, null, null, 5));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergFlyerArmorsLevel3");
    }

    @Test
    void humanMode_forge_groundArmorsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(180, 0, 5000, null, null, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ProtossGroundArmorsLevel1");
    }

    @Test
    void humanMode_forge_shieldsLevel2_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(180, 0, 5000, null, null, 7));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ProtossShieldsLevel2");
    }

    @Test
    void humanMode_cyberneticsCore_airWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(236, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ProtossAirWeaponsLevel1");
    }

    @Test
    void humanMode_twilightResearch_blink_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(237, 0, 5000, null, null, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("BlinkTech");
    }

    @Test
    void humanMode_twilightResearch_adeptPiercing_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(237, 0, 5000, null, null, 2));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("AdeptPiercingAttack");
    }

    @Test
    void humanMode_roboticsBay_extendedThermalLance_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(181, 0, 5000, null, null, 5));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ExtendedThermalLance");
    }

    // --- Second-pass gap-filling ---

    @Test
    void humanMode_combatShield_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(124, 0, 5000, null, null, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ShieldWall");
    }

    @Test
    void humanMode_evoChamber_missileWeaponsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(185, 0, 5000, null, null, 6));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergMissileWeaponsLevel1");
    }

    @Test
    void humanMode_evoChamber_missileWeaponsLevel2_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(185, 0, 5000, null, null, 7));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ZergMissileWeaponsLevel2");
    }

    @Test
    void humanMode_spawningPool_zerglingSpeed_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(190, 0, 5000, null, null, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("zerglingmovementspeed");
    }

    @Test
    void humanMode_spawningPool_zerglingAttackSpeed_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(190, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("zerglingattackspeed");
    }

    @Test
    void humanMode_hatcheryUpgrade_overlordSpeed_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(189, 0, 5000, null, null, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("overlordspeed");
    }

    @Test
    void humanMode_ultraliskCavern_anabolicSynthesis_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(117, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("AnabolicSynthesis");
    }

    @Test
    void humanMode_cyberneticsCore_airArmorsLevel1_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(236, 0, 5000, null, null, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ProtossAirArmorsLevel1");
    }

    // --- InfestationPit (abilLink=223, shares with OVERSEER_MORPH_T) ---

    @Test
    void humanMode_infestationPit_infestorEnergy_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(223, 0, 5000, null, null, 2));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("InfestorEnergyUpgrade");
    }

    @Test
    void humanMode_infestationPit_neuralParasite_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(223, 0, 5000, null, null, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("NeuralParasite");
    }

    @Test
    void humanMode_overseerMorphT_idx0_stillProducesMorphCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        m.setSelectionForTest(0, List.of("r-o-1"));
        var result = m.process(fakeCmdEvent(223, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Overlord");
        assertThat(mc.targetName()).isEqualTo("Overseer");
    }

    // --- Pair-filtered tight-window discoveries ---

    @Test
    void humanMode_hatcheryUpgrade_burrow_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.ZERG);
        var result = m.process(fakeCmdEvent(189, 0, 5000, null, null, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("Burrow");
    }

    @Test
    void humanMode_factoryTechLab_drillClaws_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(166, 0, 5000, null, null, 4));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("DrillClaws");
    }

    @Test
    void humanMode_starportTechLab_medivacSpeedBoost_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(167, 0, 5000, null, null, 14));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("MedivacIncreaseSpeedBoost");
    }

    // --- GhostAcademy (abilLink=170 for Terran, same value as Protoss probe build) ---

    @Test
    void humanMode_ghostAcademy_personalCloaking_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(170, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("PersonalCloaking");
    }

    @Test
    void humanMode_probeBuild_protoss_stillWorks() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(170, 0, 700, new float[]{50f, 50f}, null, 1));
        assertThat(result).hasSize(1);
        var bc = (ReplayCommand.BuildCommand) result.get(0);
        assertThat(bc.buildingName()).isEqualTo("Pylon");
    }

    // --- Tournament-era EngBay (abilLink=164) ---

    @Test
    void humanMode_engBayTournament_infantryWeaponsLevel2_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(164, 0, 5000, null, null, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranInfantryWeaponsLevel2");
    }

    // --- DarkShrine (abilLink=177) and FleetBeacon (abilLink=71) ---

    @Test
    void humanMode_darkShrine_darkTemplarBlink_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(177, 0, 5000, null, null, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("DarkTemplarBlinkUpgrade");
    }

    @Test
    void humanMode_fleetBeacon_phoenixRange_producesUpgradeCommand() {
        var m = new AbilityMapping(1, true, Race.PROTOSS);
        var result = m.process(fakeCmdEvent(71, 0, 5000, null, null, 2));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("PhoenixRangeUpgrade");
    }

    // --- Upgrade unknown idx returns empty ---

    @Test
    void humanMode_upgrade_unknownIdx_returnsEmpty() {
        var m = new AbilityMapping(1, true, Race.TERRAN);
        var result = m.process(fakeCmdEvent(162, 0, 5000, null, null, 99));
        assertThat(result).isEmpty();
    }

// --- Tournament-era shifted abilLink tests ---

    @Test
    void humanMode_ravagerMorph_tournamentAbilLink_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-r-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_RAVAGER_MORPH_TOURNAMENT, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Roach");
        assertThat(mc.targetName()).isEqualTo("Ravager");
    }

    @Test
    void humanMode_broodlordMorph_tournamentAbilLink_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-c-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_BROODLORD_MORPH_TOURNAMENT, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Corruptor");
        assertThat(mc.targetName()).isEqualTo("BroodLord");
    }

    @Test
    void humanMode_overseerMorph_tournamentAbilLink_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-o-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_OVERSEER_MORPH_TOURNAMENT, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Overlord");
        assertThat(mc.targetName()).isEqualTo("Overseer");
    }

    @Test
    void humanMode_banelingMorph_tournamentAbilLink_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-z-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_BANELING_MORPH_TOURNAMENT, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Zergling");
        assertThat(mc.targetName()).isEqualTo("Baneling");
    }

    @Test
    void humanMode_lurkerMorph_tournamentAbilLink_producesMorphCommand() {
        var zergMapping = new AbilityMapping(1, true, Race.ZERG);
        zergMapping.setSelectionForTest(0, List.of("r-h-1"));
        var result = zergMapping.process(fakeCmdEvent(ABIL_LURKER_MORPH_TOURNAMENT, 0, 1000, null, null, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Hydralisk");
        assertThat(mc.targetName()).isEqualTo("Lurker");
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
