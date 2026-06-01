package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZergEmulatedGameTest {

    EmulatedGame game;
    ZergRaceModel model;

    @BeforeEach
    void setUp() {
        model = new ZergRaceModel();
        game  = new EmulatedGame();
        game.setPlayerRaceModel(model);
        game.reset();
    }

    // --- Initial state ---

    @Test
    void initialState_zerg_has12DronesAndHatchery() {
        final GameState gs = game.snapshot();
        final long drones = gs.myUnits().stream().filter(u -> u.type() == UnitType.DRONE).count();
        assertThat(drones).isEqualTo(12);
        final long hatcheries = gs.myBuildings().stream()
            .filter(b -> b.type() == BuildingType.HATCHERY).count();
        assertThat(hatcheries).isEqualTo(1);
    }

    @Test
    void initialState_zerg_hasOneOverlord() {
        final long overlords = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.OVERLORD).count();
        assertThat(overlords).isEqualTo(1);
    }

    @Test
    void initialState_zerg_supply14_supplyUsed12() {
        final GameState gs = game.snapshot();
        assertThat(gs.supply()).isEqualTo(14);
        assertThat(gs.supplyUsed()).isEqualTo(12);
    }

    @Test
    void initialState_zerg_minerals50() {
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    @Test
    void initialState_hatchery_hasThreeLarva() {
        final String hatcheryTag = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.HATCHERY)
            .findFirst().orElseThrow().tag();
        assertThat(model.larvaCount(hatcheryTag)).isEqualTo(3);
    }

    // --- Worker income ---

    @Test
    void mineralIncome_zerg_sameRateAsProtoss() {
        final EmulatedGame protossGame = new EmulatedGame();
        protossGame.setPlayerRaceModel(new ProtossRaceModel());
        protossGame.reset();

        game.setMiningProbesPerBase(12);
        protossGame.setMiningProbesPerBase(12);

        game.tick();
        protossGame.tick();

        assertThat(game.snapshot().minerals()).isEqualTo(protossGame.snapshot().minerals());
    }

    // --- Larva regeneration ---

    @Test
    void larvaRegeneration_belowCap_gainsOneAfterRegenInterval() {
        final String hatchTag = hatcheryTag();

        // Drain larva to 2 by training one unit
        game.setMineralsForTesting(200);
        game.setSupplyForTesting(20, 12);
        game.applyIntent(new TrainIntent(hatchTag, UnitType.DRONE));
        assertThat(model.larvaCount(hatchTag)).isEqualTo(2);

        // Tick past one regen interval (245 loops / 22 = 11 ticks, ceiling = 12)
        final int regenTicks = (int)(ZergRaceModel.LARVA_REGEN_LOOPS / SC2Data.LOOPS_PER_TICK) + 1;
        for (int i = 0; i < regenTicks; i++) game.tick();

        assertThat(model.larvaCount(hatchTag)).isEqualTo(3);
    }

    @Test
    void larvaCapAt3_noRegenBeyondCap() {
        final String hatchTag = hatcheryTag();
        // Already at 3 — regen should not exceed cap
        assertThat(model.larvaCount(hatchTag)).isEqualTo(3);

        final int regenTicks = (int)(ZergRaceModel.LARVA_REGEN_LOOPS / SC2Data.LOOPS_PER_TICK) + 2;
        for (int i = 0; i < regenTicks; i++) game.tick();

        assertThat(model.larvaCount(hatchTag)).isEqualTo(3);
    }

    // --- Training and EGG lifecycle ---

    @Test
    void trainZergling_consumesLarva_spawnsEgg() {
        game.setMineralsForTesting(200);
        game.setSupplyForTesting(20, 12);
        final String hatchTag = hatcheryTag();

        game.applyIntent(new TrainIntent(hatchTag, UnitType.ZERGLING));

        assertThat(model.larvaCount(hatchTag)).isEqualTo(2); // one larva consumed

        final long eggs = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.EGG).count();
        assertThat(eggs).isEqualTo(1);
    }

    @Test
    void eggHatches_spawnsTwoZerglings_eggRemoved() {
        game.setMineralsForTesting(200);
        game.setSupplyForTesting(20, 12);
        final String hatchTag = hatcheryTag();

        game.applyIntent(new TrainIntent(hatchTag, UnitType.ZERGLING));

        final int buildTicks = SC2Data.trainTimeInTicks(UnitType.ZERGLING);
        for (int i = 0; i < buildTicks; i++) game.tick();

        final long zerglings = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.ZERGLING).count();
        assertThat(zerglings).isEqualTo(2);

        final long eggs = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.EGG).count();
        assertThat(eggs).isEqualTo(0);
    }

    @Test
    void trainRoach_noLarva_rejected_noResourcesDeducted() {
        game.setMineralsForTesting(500);
        game.setVespeneForHarness(200);
        game.setSupplyForTesting(30, 12);
        final String hatchTag = hatcheryTag();

        // Drain all larva
        for (int i = 0; i < 3; i++) {
            game.applyIntent(new TrainIntent(hatchTag, UnitType.DRONE));
        }
        assertThat(model.larvaCount(hatchTag)).isEqualTo(0);

        final int mineralsBeforeReject = game.snapshot().minerals();
        final int supplyUsedBeforeReject = game.snapshot().supplyUsed();

        // TrainIntent with no larva — must be rejected
        game.applyIntent(new TrainIntent(hatchTag, UnitType.ROACH));

        // No resources deducted
        assertThat(game.snapshot().minerals()).isEqualTo(mineralsBeforeReject);
        assertThat(game.snapshot().supplyUsed()).isEqualTo(supplyUsedBeforeReject);
    }

    // --- Overlord supply ---

    @Test
    void trainOverlord_addsSupply_onSpawn() {
        game.setMineralsForTesting(200);
        final String hatchTag = hatcheryTag();
        final int supplyBefore = game.snapshot().supply();

        game.applyIntent(new TrainIntent(hatchTag, UnitType.OVERLORD));

        final int buildTicks = SC2Data.trainTimeInTicks(UnitType.OVERLORD);
        for (int i = 0; i < buildTicks; i++) game.tick();

        assertThat(game.snapshot().supply()).isEqualTo(supplyBefore + 8);
    }

    // --- Queen energy and inject ---

    @Test
    void queenEnergyRegens_perTick() {
        game.setMineralsForTesting(300);
        game.setSupplyForTesting(30, 12);
        final String hatchTag = hatcheryTag();

        // Train a Queen
        game.applyIntent(new TrainIntent(hatchTag, UnitType.QUEEN));
        final int buildTicks = SC2Data.trainTimeInTicks(UnitType.QUEEN);
        for (int i = 0; i < buildTicks; i++) game.tick();

        // Find the queen
        final String queenTag = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.QUEEN)
            .findFirst().orElseThrow().tag();

        // Set energy below inject threshold so auto-inject doesn't interfere with regen measurement
        model.setQueenEnergyForTesting(queenTag, 10.0);
        final double energyAtLow = model.queenEnergy(queenTag);
        game.tick();
        final double energyAfterTick = model.queenEnergy(queenTag);

        assertThat(energyAfterTick).isGreaterThan(energyAtLow);
        assertThat(energyAfterTick).isLessThan(ZergRaceModel.INJECT_COST_ENERGY); // inject didn't fire
    }

    @Test
    void queenInject_consumesEnergy_addsLarvaToNearestHatchery() {
        game.setMineralsForTesting(300);
        game.setSupplyForTesting(30, 12);
        final String hatchTag = hatcheryTag();

        // Drain only 2 larva — leave 1 for Queen training
        game.applyIntent(new TrainIntent(hatchTag, UnitType.DRONE));
        game.applyIntent(new TrainIntent(hatchTag, UnitType.DRONE));
        assertThat(model.larvaCount(hatchTag)).isEqualTo(1);

        // Train a Queen with the remaining larva
        game.applyIntent(new TrainIntent(hatchTag, UnitType.QUEEN));
        assertThat(model.larvaCount(hatchTag)).isEqualTo(0);

        // Queen is 3rd in queue: Drone(12) + Drone(12) + Queen(40) = 64 ticks min
        final int droneTicks  = SC2Data.trainTimeInTicks(UnitType.DRONE);
        final int queenTicks  = SC2Data.trainTimeInTicks(UnitType.QUEEN);
        final int totalTicks  = droneTicks + droneTicks + queenTicks + 2; // +2 buffer
        for (int i = 0; i < totalTicks; i++) game.tick();

        final String queenTag = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.QUEEN)
            .findFirst().orElseThrow().tag();

        // Set energy to exactly inject threshold and record larva count
        model.setQueenEnergyForTesting(queenTag, ZergRaceModel.INJECT_COST_ENERGY);
        final int larvaBeforeInject = model.larvaCount(hatchTag);

        game.tick(); // regen pushes energy past threshold → inject fires

        final int larvaAfterInject = model.larvaCount(hatchTag);
        assertThat(larvaAfterInject - larvaBeforeInject).isEqualTo(ZergRaceModel.INJECT_LARVA_COUNT);
        assertThat(model.queenEnergy(queenTag)).isLessThan(ZergRaceModel.INJECT_COST_ENERGY);
    }

    @Test
    void injectLarvaCap_at19() {
        game.setMineralsForTesting(300);
        game.setSupplyForTesting(30, 12);
        final String hatchTag = hatcheryTag();

        // Inject many times by ticking past energy threshold repeatedly
        game.applyIntent(new TrainIntent(hatchTag, UnitType.QUEEN));
        final int buildTicks = SC2Data.trainTimeInTicks(UnitType.QUEEN);
        for (int i = 0; i < buildTicks; i++) game.tick();

        // Drain larva first
        for (int i = 0; i < 3; i++) game.applyIntent(new TrainIntent(hatchTag, UnitType.DRONE));

        // Force many injections by setting high energy via a direct energy loop
        // Tick many times to accumulate enough energy for multiple injects
        for (int i = 0; i < 200; i++) game.tick();

        assertThat(model.larvaCount(hatchTag)).isLessThanOrEqualTo(ZergRaceModel.MAX_INJECT_LARVA);
    }

    // --- Town hall worker assignment ---

    @Test
    void hatchery_countsForWorkerAssignment() {
        final int[] counts = EmulatedGame.countWorkersPerBase(Race.ZERG,
            game.snapshot().myBuildings(), game.snapshot().myUnits());
        // 12 Drones all near Hatchery at (8,8)
        assertThat(counts).containsExactly(12);
    }

    @Test
    void lairAndHive_alsoCountAsTownHalls() {
        // Spawn a LAIR building — should count as a base for income
        game.spawnBuildingForTesting(BuildingType.LAIR, new Point2d(30, 30));
        final int[] counts = EmulatedGame.countWorkersPerBase(Race.ZERG,
            game.snapshot().myBuildings(), game.snapshot().myUnits());
        assertThat(counts).hasSize(2); // HATCHERY + LAIR
        assertThat(counts[0]).isEqualTo(12); // all Drones near Hatchery at (8,8)
        assertThat(counts[1]).isEqualTo(0);
    }

    // --- Multiple hatcheries ---

    @Test
    void multipleHatcheries_separateLarvaCounters() {
        // Spawn a second hatchery
        final Building hatch2 = game.spawnBuildingForTesting(BuildingType.HATCHERY, new Point2d(30, 30));
        // New hatchery starts with 0 larva (not seeded by seedInitialState)
        assertThat(model.larvaCount(hatch2.tag())).isEqualTo(0);

        // Original hatchery still has its larva intact
        assertThat(model.larvaCount(hatcheryTag())).isEqualTo(3);
    }

    // --- Reset clears ZergRaceModel state ---

    @Test
    void reset_clearsZergRaceModelState() {
        game.setMineralsForTesting(500);
        game.setSupplyForTesting(30, 12);
        final String hatchTag = hatcheryTag();

        // Dirty the model: consume 2 larva, spawn EGGs
        game.applyIntent(new TrainIntent(hatchTag, UnitType.DRONE));
        game.applyIntent(new TrainIntent(hatchTag, UnitType.DRONE));
        assertThat(model.larvaCount(hatchTag)).isEqualTo(1);
        assertThat(model.eggPendingForBuilding(hatchTag)).isTrue();

        // Reset — model must clear its internal state
        game.reset();

        // After reset: larva back to 3, no pending EGGs
        final String newHatchTag = hatcheryTag();
        assertThat(model.larvaCount(newHatchTag)).isEqualTo(3);
        assertThat(model.eggPendingForBuilding(newHatchTag)).isFalse();

        // State is consistent — hatchery has initial 3 larva
        assertThat(game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.EGG).count()).isEqualTo(0);
    }

    // --- canProduce view-enforcement tests ---

    @Test
    void canProduce_withView_noLarva_returnsBlocked() {
        final ZergRaceModel model = new ZergRaceModel();
        final PlayerState state = new PlayerState();
        // hatcheryLarvaCount is empty — BLOCKED regardless of PlayerStateView content
        assertThat(model.canProduce(state, "hatch-0", UnitType.DRONE))
            .isEqualTo(ProductionDecision.BLOCKED);
    }

    @Test
    void canProduce_withView_larvaAvailable_returnsProceed() {
        final ZergRaceModel model = new ZergRaceModel();
        final PlayerState state = new PlayerState();
        model.seedInitialState(state, new ArrayList<>());

        assertThat(model.canProduce(state, "hatchery-0", UnitType.DRONE))
            .isEqualTo(ProductionDecision.PROCEED);
    }

    // --- Helpers ---

    private String hatcheryTag() {
        return game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.HATCHERY)
            .findFirst().orElseThrow().tag();
    }
}
