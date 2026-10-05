package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UpgradeType;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchIntentTest {

    private EmulatedGame game;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.reset();
    }

    @Test
    void researchCompletesAfterDrainTime() {
        Building forge = injectBuilding(BuildingType.FORGE, "forge-1");
        int durationLoops = SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1);
        int durationTicks = durationLoops / SC2Data.LOOPS_PER_TICK;

        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-1", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));

        GameState before = game.snapshot();
        assertThat(before.playerUpgrades()).doesNotContain("ProtossGroundWeaponsLevel1");

        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }

        GameState after = game.snapshot();
        assertThat(after.playerUpgrades()).contains("ProtossGroundWeaponsLevel1");
    }

    @Test
    void researchNotCompleteBeforeDrainTime() {
        Building forge = injectBuilding(BuildingType.FORGE, "forge-1");
        int durationLoops = SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1);
        int halfTicks = (durationLoops / SC2Data.LOOPS_PER_TICK) / 2;

        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-1", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));

        for (int i = 0; i < halfTicks; i++) {
            game.tick();
        }

        GameState midway = game.snapshot();
        assertThat(midway.playerUpgrades()).doesNotContain("ProtossGroundWeaponsLevel1");
    }

    @Test
    void duplicateResearchRejected() {
        injectBuilding(BuildingType.FORGE, "forge-1");
        injectBuilding(BuildingType.FORGE, "forge-2");

        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-1", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));

        int durationTicks = SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1) / SC2Data.LOOPS_PER_TICK;
        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }

        assertThat(game.snapshot().playerUpgrades()).contains("ProtossGroundWeaponsLevel1");

        game.applyIntent(new TimedIntent(
            (long) (durationTicks + 2) * SC2Data.LOOPS_PER_TICK,
            new ResearchIntent("forge-2", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));

        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }

        assertThat(game.snapshot().playerUpgrades()).contains("ProtossGroundWeaponsLevel1");
    }

    @Test
    void oneBuildingCannotResearchTwoSimultaneously() {
        injectBuilding(BuildingType.FORGE, "forge-1");

        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-1", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));
        game.applyIntent(new TimedIntent(1, new ResearchIntent("forge-1", UpgradeType.PROTOSS_GROUND_ARMORS_1)));

        int durationTicks = SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1) / SC2Data.LOOPS_PER_TICK;
        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }

        GameState state = game.snapshot();
        assertThat(state.playerUpgrades()).contains("ProtossGroundWeaponsLevel1");
        assertThat(state.playerUpgrades()).doesNotContain("ProtossGroundArmorsLevel1");
    }

    @Test
    void researchRejectedOnIncompleteBuilding() {
        Building incomplete = new Building("forge-inc", BuildingType.FORGE,
            new Point2d(30, 30), 400, 400, false);
        game.injectReplayBuilding(incomplete);

        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-inc", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));

        int durationTicks = SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1) / SC2Data.LOOPS_PER_TICK;
        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }

        assertThat(game.snapshot().playerUpgrades()).doesNotContain("ProtossGroundWeaponsLevel1");
    }

    @Test
    void researchRejectedOnMissingBuilding() {
        game.applyIntent(new TimedIntent(0, new ResearchIntent("nonexistent", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));

        int durationTicks = SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1) / SC2Data.LOOPS_PER_TICK;
        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }

        assertThat(game.snapshot().playerUpgrades()).doesNotContain("ProtossGroundWeaponsLevel1");
    }

    @Test
    void twoBuildingsCanResearchDifferentUpgradesSimultaneously() {
        injectBuilding(BuildingType.FORGE, "forge-1");
        injectBuilding(BuildingType.FORGE, "forge-2");

        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-1", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));
        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-2", UpgradeType.PROTOSS_GROUND_ARMORS_1)));

        int maxDuration = Math.max(
            SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1),
            SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_ARMORS_1));
        int durationTicks = maxDuration / SC2Data.LOOPS_PER_TICK;
        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }

        GameState state = game.snapshot();
        assertThat(state.playerUpgrades()).contains("ProtossGroundWeaponsLevel1", "ProtossGroundArmorsLevel1");
    }

    @Test
    void upgradeStateResetOnGameReset() {
        injectBuilding(BuildingType.FORGE, "forge-1");

        game.applyIntent(new TimedIntent(0, new ResearchIntent("forge-1", UpgradeType.PROTOSS_GROUND_WEAPONS_1)));
        int durationTicks = SC2Data.upgradeTimeInLoops(UpgradeType.PROTOSS_GROUND_WEAPONS_1) / SC2Data.LOOPS_PER_TICK;
        for (int i = 0; i < durationTicks + 1; i++) {
            game.tick();
        }
        assertThat(game.snapshot().playerUpgrades()).contains("ProtossGroundWeaponsLevel1");

        game.reset();
        assertThat(game.snapshot().playerUpgrades()).isEmpty();
    }

    private Building injectBuilding(BuildingType type, String tag) {
        Building b = new Building(tag, type, new Point2d(30, 30), 400, 400, true);
        game.injectReplayBuilding(b);
        return b;
    }
}
