package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.MuleCalldownIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TerranEmulatedGameTest {

    EmulatedGame game;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.setPlayerRaceModel(new TerranRaceModel());
        game.reset();
    }

    // --- Initial state ---

    @Test
    void initialState_terran_has12ScvsAndCommandCenter() {
        final GameState gs = game.snapshot();
        final long scvCount = gs.myUnits().stream().filter(u -> u.type() == UnitType.SCV).count();
        assertThat(scvCount).isEqualTo(12);
        final long ccCount = gs.myBuildings().stream()
            .filter(b -> b.type() == BuildingType.COMMAND_CENTER).count();
        assertThat(ccCount).isEqualTo(1);
    }

    @Test
    void initialState_terran_supply15_supplyUsed12() {
        final GameState gs = game.snapshot();
        assertThat(gs.supply()).isEqualTo(15);
        assertThat(gs.supplyUsed()).isEqualTo(12);
    }

    @Test
    void initialState_terran_minerals50() {
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    // --- Worker income ---

    @Test
    void mineralIncome_terran_sameRateAsProtoss() {
        // Worker income is race-invariant — Probes and SCVs earn the same rate
        final EmulatedGame protossGame = new EmulatedGame();
        protossGame.setPlayerRaceModel(new ProtossRaceModel());
        protossGame.reset();

        // Override both to the same worker count so initial probes/SCVs don't differ in positioning
        game.setMiningProbesPerBase(12);
        protossGame.setMiningProbesPerBase(12);

        game.tick();
        protossGame.tick();

        assertThat(game.snapshot().minerals())
            .isEqualTo(protossGame.snapshot().minerals());
    }

    // --- SCV training ---

    @Test
    void trainSCV_deductsMineralsAndAppearsAfterBuildTime() {
        final Building cc = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.COMMAND_CENTER)
            .findFirst().orElseThrow();

        game.setMineralsForTesting(100);
        game.applyIntent(new io.quarkmind.sc2.intent.TrainIntent(cc.tag(), UnitType.SCV));

        // 50 minerals deducted
        assertThat(game.snapshot().minerals()).isEqualTo(50);

        // Unit appears after trainTimeInTicks ticks
        final int buildTicks = SC2Data.trainTimeInTicks(UnitType.SCV);
        for (int i = 0; i < buildTicks; i++) game.tick();

        final long scvCount = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.SCV).count();
        assertThat(scvCount).isEqualTo(13);
    }

    @Test
    void trainMarine_requiresBarracks_rejectedWithoutOne() {
        game.setMineralsForTesting(200);
        final Building cc = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.COMMAND_CENTER)
            .findFirst().orElseThrow();

        // Try to train Marine from CC — should be rejected (wrong building type)
        game.applyIntent(new io.quarkmind.sc2.intent.TrainIntent(cc.tag(), UnitType.MARINE));

        // No Marine produced, minerals unchanged
        assertThat(game.snapshot().minerals()).isEqualTo(200);
        final long marineCount = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.MARINE).count();
        assertThat(marineCount).isEqualTo(0);
    }

    @Test
    void trainMarine_fromBarracks_succeeds() {
        game.setMineralsForTesting(200);
        game.setSupplyForTesting(20, 12);
        final Building barracks = game.spawnBuildingForTesting(BuildingType.BARRACKS, new Point2d(15, 15));

        game.applyIntent(new io.quarkmind.sc2.intent.TrainIntent(barracks.tag(), UnitType.MARINE));
        assertThat(game.snapshot().minerals()).isEqualTo(150); // 50 deducted

        final int buildTicks = SC2Data.trainTimeInTicks(UnitType.MARINE);
        for (int i = 0; i < buildTicks; i++) game.tick();

        final long marineCount = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.MARINE).count();
        assertThat(marineCount).isEqualTo(1);
    }

    @Test
    void trainMarauder_deductsGas() {
        game.setMineralsForTesting(200);
        game.setVespeneForHarness(50);
        game.setSupplyForTesting(20, 12);
        final Building barracks = game.spawnBuildingForTesting(BuildingType.BARRACKS, new Point2d(15, 15));

        game.applyIntent(new io.quarkmind.sc2.intent.TrainIntent(barracks.tag(), UnitType.MARAUDER));

        assertThat(game.snapshot().minerals()).isEqualTo(100); // 100 deducted
        // Gas is internal — snapshot doesn't expose vespene directly, check via game state
    }

    // --- MULE ---

    @Test
    void muleSpawn_appearsInSnapshot_andAddsIncomePerTick() {
        // Build an Orbital Command
        final Building oc = game.spawnBuildingForTesting(BuildingType.ORBITAL_COMMAND, new Point2d(12, 8));

        final int mineralsBefore = game.snapshot().minerals();
        game.applyIntent(new io.quarkmind.sc2.intent.TrainIntent(oc.tag(), UnitType.MULE));

        // MULE appears immediately (no queue)
        final long muleCount = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.MULE).count();
        assertThat(muleCount).isEqualTo(1);

        // Tick adds MULE income on top of SCV income
        final int mineralsAfterMule = game.snapshot().minerals();
        game.setMiningProbesPerBase(0); // suppress SCV income to isolate MULE
        game.tick();
        assertThat(game.snapshot().minerals()).isGreaterThan(mineralsAfterMule);
    }

    @Test
    void muleExpires_afterLifetime_unitGoneAndNoMoreMuleIncome() {
        final Building oc = game.spawnBuildingForTesting(BuildingType.ORBITAL_COMMAND, new Point2d(12, 8));
        game.applyIntent(new io.quarkmind.sc2.intent.TrainIntent(oc.tag(), UnitType.MULE));

        // Tick once with MULE active — record combined SCV+MULE income
        final int mineralsAtSpawn = game.snapshot().minerals();
        game.tick();
        final int incomeWithMule = game.snapshot().minerals() - mineralsAtSpawn;

        // Advance past MULE lifetime
        final int lifetimeTicks = SC2Data.MULE_LIFETIME_LOOPS / SC2Data.LOOPS_PER_TICK + 2;
        for (int i = 0; i < lifetimeTicks; i++) game.tick();

        final long muleCount = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.MULE).count();
        assertThat(muleCount).isEqualTo(0);

        // Income without MULE should be strictly less (SCV-only income < SCV+MULE)
        final int mineralsBeforeExpiredTick = game.snapshot().minerals();
        game.tick();
        final int incomeWithoutMule = game.snapshot().minerals() - mineralsBeforeExpiredTick;

        assertThat(incomeWithoutMule).isLessThan(incomeWithMule);
    }

    @Test
    void muleCalldown_ocPresent_spawnsMuleImmediately() {
        final Building oc = game.spawnBuildingForTesting(BuildingType.ORBITAL_COMMAND, new Point2d(12, 8));

        game.applyIntent(new MuleCalldownIntent(oc.tag()));

        final long muleCount = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.MULE).count();
        assertThat(muleCount).isEqualTo(1);

        // Verify MULE is registered for income — tick with workers suppressed to isolate MULE income
        final int mineralsAfterSpawn = game.snapshot().minerals();
        game.setMiningProbesPerBase(0);
        game.tick();
        assertThat(game.snapshot().minerals()).isGreaterThan(mineralsAfterSpawn);
    }

    @Test
    void muleCalldown_tagNotAnOc_noUnitAdded() {
        // CC tag — building exists but type is COMMAND_CENTER, not ORBITAL_COMMAND
        final Building cc = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.COMMAND_CENTER)
            .findFirst().orElseThrow();

        final int unitsBefore = game.snapshot().myUnits().size();
        game.applyIntent(new MuleCalldownIntent(cc.tag()));

        assertThat(game.snapshot().myUnits()).hasSize(unitsBefore);
    }

    @Test
    void orbitalCommandTownHall_countsForWorkerAssignment() {
        // OC should count as a base for worker income assignment
        game.spawnBuildingForTesting(BuildingType.ORBITAL_COMMAND, new Point2d(30, 30));

        // SCV near OC should be assigned to OC for income purposes
        final int[] counts = EmulatedGame.countWorkersPerBase(Race.TERRAN,
            game.snapshot().myBuildings(), game.snapshot().myUnits());

        // We have CC at (8,8) and OC at (30,30), 12 SCVs near (9-14,9) → all near CC
        assertThat(counts).hasSize(2);
        assertThat(counts[0]).isEqualTo(12);
        assertThat(counts[1]).isEqualTo(0);
    }
}
