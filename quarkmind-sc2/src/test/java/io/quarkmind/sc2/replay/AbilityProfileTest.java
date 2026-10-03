package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.sllauncher.util.Pair;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AbilityProfileTest {

    @Test
    void resolve_oracleBaseBuild_returnsV4_9_3() {
        assertThat(AbilityProfile.resolve(75689)).isEqualTo(AbilityProfile.V4_9_3);
        assertThat(AbilityProfile.resolve(75025)).isEqualTo(AbilityProfile.V4_9_3);
    }

    @Test
    void resolve_tournamentBaseBuild_returnsHSC2025() {
        assertThat(AbilityProfile.resolve(94137)).isEqualTo(AbilityProfile.HSC_2025);
    }

    @Test
    void v4_9_3_hasEmptyOverrides() {
        assertThat(AbilityProfile.V4_9_3.overrides()).isEmpty();
    }

    @Test
    void hsc2025_hasNonEmptyOverrides() {
        assertThat(AbilityProfile.HSC_2025.overrides()).isNotEmpty();
    }

    @Test
    void abilityMapping_withProfile_usesDefaultDispatch() {
        var mapping = new AbilityMapping(1, true, Race.ZERG, AbilityProfile.V4_9_3);
        mapping.setSelectionForTest(0, List.of("r-z-1"));
        var result = mapping.process(fakeCmdEvent(73, 0, 1000, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Zergling");
        assertThat(mc.targetName()).isEqualTo("Baneling");
    }

    @Test
    void hsc2025_banelingMorph_overrideDispatches() {
        var mapping = new AbilityMapping(1, true, Race.ZERG, AbilityProfile.HSC_2025);
        mapping.setSelectionForTest(0, List.of("r-z-1"));
        var result = mapping.process(fakeCmdEvent(730, 0, 1000, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Zergling");
        assertThat(mc.targetName()).isEqualTo("Baneling");
    }

    @Test
    void hsc2025_ravagerMorph_overrideDispatches() {
        var mapping = new AbilityMapping(1, true, Race.ZERG, AbilityProfile.HSC_2025);
        mapping.setSelectionForTest(0, List.of("r-r-1"));
        var result = mapping.process(fakeCmdEvent(311, 0, 1000, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Roach");
        assertThat(mc.targetName()).isEqualTo("Ravager");
    }

    @Test
    void hsc2025_overseerMorph_idx0_dispatches() {
        var mapping = new AbilityMapping(1, true, Race.ZERG, AbilityProfile.HSC_2025);
        mapping.setSelectionForTest(0, List.of("r-o-1"));
        var result = mapping.process(fakeCmdEvent(223, 0, 1000, 0));
        assertThat(result).hasSize(1);
        var mc = (ReplayCommand.MorphCommand) result.get(0);
        assertThat(mc.sourceName()).isEqualTo("Overlord");
        assertThat(mc.targetName()).isEqualTo("Overseer");
    }

    @Test
    void hsc2025_overseerMorph_idx2_infestorEnergy() {
        var mapping = new AbilityMapping(1, true, Race.ZERG, AbilityProfile.HSC_2025);
        var result = mapping.process(fakeCmdEvent(223, 0, 5000, 2));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("InfestorEnergyUpgrade");
    }

    @Test
    void hsc2025_forgeConflict_abilLink182_groundWeapons1() {
        var mapping = new AbilityMapping(1, true, Race.PROTOSS, AbilityProfile.HSC_2025);
        var result = mapping.process(fakeCmdEvent(182, 0, 5000, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("ProtossGroundWeaponsLevel1");
    }

    @Test
    void hsc2025_spawningPoolConflict_abilLink192_zerglingSpeed() {
        var mapping = new AbilityMapping(1, true, Race.ZERG, AbilityProfile.HSC_2025);
        var result = mapping.process(fakeCmdEvent(192, 0, 5000, 1));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("zerglingmovementspeed");
    }

    @Test
    void hsc2025_cyberneticsCoreConflict_abilLink174_warpGateResearch() {
        var mapping = new AbilityMapping(1, true, Race.PROTOSS, AbilityProfile.HSC_2025);
        var result = mapping.process(fakeCmdEvent(174, 0, 5000, 6));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("WarpGateResearch");
    }

    @Test
    void hsc2025_engBayTournament_infantryWeapons2() {
        var mapping = new AbilityMapping(1, true, Race.TERRAN, AbilityProfile.HSC_2025);
        var result = mapping.process(fakeCmdEvent(164, 0, 5000, 3));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("TerranInfantryWeaponsLevel2");
    }

    @Test
    void hsc2025_hydraliskDenTournament_groovedSpines() {
        var mapping = new AbilityMapping(1, true, Race.ZERG, AbilityProfile.HSC_2025);
        var result = mapping.process(fakeCmdEvent(191, 0, 5000, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("EvolveGroovedSpines");
    }

    @Test
    void hsc2025_barracksTechLabTournament_stimpack() {
        var mapping = new AbilityMapping(1, true, Race.TERRAN, AbilityProfile.HSC_2025);
        var result = mapping.process(fakeCmdEvent(165, 0, 5000, 0));
        assertThat(result).hasSize(1);
        var uc = (ReplayCommand.UpgradeCommand) result.get(0);
        assertThat(uc.upgradeName()).isEqualTo("Stimpack");
    }

    private CmdEvent fakeCmdEvent(int abilLink, int userId, long loop, int abilCmdIndex) {
        Map<String, Object> struct = new HashMap<>();
        Map<String, Object> abil = new HashMap<>();
        abil.put("abilLink", abilLink);
        abil.put("abilCmdIndex", abilCmdIndex);
        struct.put("abil", abil);
        struct.put("cmdFlags", 0);
        return new CmdEvent(struct, 27, "BasicCommandEvent", (int) loop, userId, 99999, null);
    }
}
