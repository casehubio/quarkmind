package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.quarkmind.plugin.tactics.TerrainAwareKiteStrategy;

class EmulatedGameTest {

    EmulatedGame game;

    /**
     * Minimal EnemyStrategy implementation for E6 retreat tests.
     * Only attackConfig() is needed — tickEnemyRetreat() is the only consumer.
     * The economy/training methods are stubs; they are tested separately once
     * EnemyBehavior + FixedBuildOrderStrategy are implemented (Task 2/5/6).
     */
    static EnemyStrategy retreatStrategy(EnemyAttackConfig atk) {
        return new EnemyStrategy() {
            @Override public String name()              { return "test"; }
            @Override public Race race()                { return Race.PROTOSS; }
            @Override public int mineralsPerTick()      { return 0; }
            @Override public EnemyAttackConfig attackConfig() { return atk; }
            @Override public Optional<UnitType> nextUnit(EnemyObservation obs) { return Optional.empty(); }
            @Override public boolean shouldSwitch(EnemyObservation obs) { return false; }
        };
    }

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT); // defer wave — doesn't fire in E1 tests
        game.reset();
    }

    // ---- E1 tests (unchanged) ----

    @Test
    void initialMineralsAreFifty() {
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    @Test
    void mineralAccumulatesWithMiningProbes() {
        int ticks = 100;
        for (int i = 0; i < ticks; i++) game.tick();
        double expected = SC2Data.INITIAL_MINERALS + SC2Data.mineralIncomePerTick(SC2Data.INITIAL_PROBES) * ticks;
        assertThat(game.snapshot().minerals()).isCloseTo((int) expected, within(1));
    }

    @Test
    void zeroProbesYieldsNoMineralGain() {
        game.setMiningProbes(0);
        for (int i = 0; i < 100; i++) game.tick();
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    @Test
    void snapshotFrameDoesNotChangeAfterTick() {
        GameState before = game.snapshot();
        game.tick();
        assertThat(before.gameFrame()).isEqualTo(0L);
        assertThat(game.snapshot().gameFrame()).isEqualTo(1L);
    }

    @Test
    void resetRestoresInitialState() {
        for (int i = 0; i < 100; i++) game.tick();
        game.reset();
        assertThat(game.snapshot().minerals()).isEqualTo(50);
        assertThat(game.snapshot().gameFrame()).isEqualTo(0L);
    }

    @Test
    void moveIntentDoesNotChangeUnitCountOrMinerals() {
        game.applyIntent(new MoveIntent("probe-0", new Point2d(10, 10)));
        assertThat(game.snapshot().minerals()).isEqualTo(50);
        assertThat(game.snapshot().myUnits()).hasSize(12);
    }

    // ---- E2: movement ----

    @Test
    void unitMovesEachTickWhenTargetSet() {
        game.applyIntent(new MoveIntent("probe-0", new Point2d(15, 9)));
        Point2d before = game.snapshot().myUnits().get(0).position();
        game.tick();
        Point2d after = game.snapshot().myUnits().get(0).position();
        assertThat(after.x()).isGreaterThan(before.x()); // moved toward x=15
    }

    @Test
    void unitArrivesAtTarget() {
        // probe-0 starts at (9, 9), target at (9.1, 9) — speed 0.5 overshoots, snaps to target
        game.applyIntent(new MoveIntent("probe-0", new Point2d(9.1f, 9)));
        game.tick();
        Point2d pos = game.snapshot().myUnits().get(0).position();
        assertThat(pos.x()).isCloseTo(9.1f, within(0.01f));
    }

    @Test
    void attackIntentSetsMovementTarget() {
        game.applyIntent(new AttackIntent("probe-0", new Point2d(20, 20)));
        Point2d before = game.snapshot().myUnits().get(0).position();
        game.tick();
        Point2d after = game.snapshot().myUnits().get(0).position();
        assertThat(after.x()).isGreaterThan(before.x());
        assertThat(after.y()).isGreaterThan(before.y());
    }

    @Test
    void stepTowardHelperMovesCorrectDistance() {
        Point2d from   = new Point2d(0, 0);
        Point2d to     = new Point2d(10, 0);
        Point2d result = EmulatedGame.stepToward(from, to, 0.5);
        assertThat(result.x()).isCloseTo(0.5f, within(0.001f));
        assertThat(result.y()).isCloseTo(0f,   within(0.001f));
    }

    @Test
    void stepTowardHelperSnapsToTargetWhenCloseEnough() {
        Point2d from   = new Point2d(0, 0);
        Point2d to     = new Point2d(0.3f, 0);
        Point2d result = EmulatedGame.stepToward(from, to, 0.5); // speed > distance
        assertThat(result.x()).isEqualTo(to.x());
        assertThat(result.y()).isEqualTo(to.y());
    }

    // ---- E2: enemy wave ----

    @Test
    void enemySpawnsAtConfiguredFrame() {
        game.configureWave(5, 2, UnitType.ZEALOT);
        game.reset();
        assertThat(game.snapshot().enemyUnits()).isEmpty();
        for (int i = 0; i < 5; i++) game.tick();
        assertThat(game.snapshot().enemyUnits()).hasSize(2);
        assertThat(game.snapshot().enemyUnits().get(0).type()).isEqualTo(UnitType.ZEALOT);
    }

    @Test
    void enemyMovesEachTickTowardNexus() {
        game.configureWave(1, 1, UnitType.ZEALOT);
        game.reset();
        game.tick(); // frame 1 — wave spawns at (26, 26)
        Point2d spawnPos = game.snapshot().enemyUnits().get(0).position();
        game.tick(); // frame 2 — enemy moves toward (8, 8)
        Point2d afterPos = game.snapshot().enemyUnits().get(0).position();
        assertThat(afterPos.x()).isLessThan(spawnPos.x());
        assertThat(afterPos.y()).isLessThan(spawnPos.y());
    }

    // ---- E2: train intent ----

    @Test
    void trainIntentDeductsMinerals() {
        game.setMineralsForTesting(200);
        Building gw = game.spawnBuildingForTesting(BuildingType.GATEWAY, new Point2d(20, 20));
        game.applyIntent(new TrainIntent(gw.tag(), UnitType.ZEALOT));
        assertThat(game.snapshot().minerals()).isEqualTo(100); // 200 - 100
    }

    @Test
    void trainedUnitAppearsAfterBuildTime() {
        game.setMineralsForTesting(500);
        Building gw = game.spawnBuildingForTesting(BuildingType.GATEWAY, new Point2d(20, 20));
        game.applyIntent(new TrainIntent(gw.tag(), UnitType.ZEALOT));
        int before = game.snapshot().myUnits().size();
        for (int i = 0; i < 28; i++) game.tick(); // Zealot = 28 ticks
        assertThat(game.snapshot().myUnits()).hasSize(before + 1);
    }

    @Test
    void trainBlockedIfInsufficientMinerals() {
        // 50 minerals, Zealot costs 100 — blocked
        Building gw = game.spawnBuildingForTesting(BuildingType.GATEWAY, new Point2d(20, 20));
        game.applyIntent(new TrainIntent(gw.tag(), UnitType.ZEALOT));
        assertThat(game.snapshot().minerals()).isEqualTo(50); // unchanged
    }

    @Test
    void setVespeneForHarness_enablesGasUnitTraining() {
        game.reset();

        // Inject a complete Gateway — Stalkers train here
        Building gateway = new Building(
            "gw-vespene-test", BuildingType.GATEWAY,
            new Point2d(12, 12),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            true);
        game.injectReplayBuilding(gateway);

        // Tick 30 times to accumulate ≥125 minerals (Stalker costs 125 minerals + 50 gas)
        for (int i = 0; i < 30; i++) game.tick();

        // Attempt 1: vespene == 0 — TrainIntent rejected at gas check
        game.applyIntent(new TrainIntent("gw-vespene-test", UnitType.STALKER));
        for (int i = 0; i < SC2Data.trainTimeInTicks(UnitType.STALKER) + 1; i++) game.tick();
        assertThat(game.snapshot().myUnits())
            .as("Stalker train must be rejected when vespene == 0")
            .noneMatch(u -> u.type() == UnitType.STALKER);

        // Attempt 2: set vespene — TrainIntent accepted
        game.setVespeneForHarness(50);
        game.applyIntent(new TrainIntent("gw-vespene-test", UnitType.STALKER));
        for (int i = 0; i < SC2Data.trainTimeInTicks(UnitType.STALKER) + 1; i++) game.tick();
        assertThat(game.snapshot().myUnits())
            .as("Stalker must be trained after setVespeneForHarness(50)")
            .anyMatch(u -> u.type() == UnitType.STALKER);
    }

    // ---- E2: build intent ----

    @Test
    void buildIntentDeductsMinerals() {
        game.setMineralsForTesting(500);
        game.applyIntent(new BuildIntent("probe-0", BuildingType.PYLON, new Point2d(15, 15)));
        assertThat(game.snapshot().minerals()).isEqualTo(400); // 500 - 100
    }

    @Test
    void buildingCompletesAfterBuildTime() {
        game.setMineralsForTesting(500);
        game.applyIntent(new BuildIntent("probe-0", BuildingType.PYLON, new Point2d(15, 15)));
        int supplyBefore = game.snapshot().supply();
        for (int i = 0; i < 18; i++) game.tick(); // Pylon = 18 ticks
        assertThat(game.snapshot().supply()).isEqualTo(supplyBefore + 8); // +8 from Pylon
    }

    // ---- E3: combat ----

    @Test
    void shieldsAbsorbDamageBeforeHp() {
        // probe-0 at (9,9) with 20 shields. Enemy Zealot at (9.0,9.3) — 0.3 tiles away, within melee range.
        // probe-1 at (9.5,9) is 0.58 tiles away — outside melee range, so probe-0 is the sole target.
        // Zealot deals 8 dmg/attack → shields take hit first (20→12), HP unchanged.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.0f, 9.3f));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE)); // HP untouched
        assertThat(probe.shields()).isLessThan(SC2Data.maxShields(UnitType.PROBE)); // shields hit
    }

    @Test
    void damageOverflowsFromShieldsToHp() {
        // probe-0 with 3 shields. Zealot at (9.0,9.3) — probe-0 is nearest target (0.3 tiles).
        // Zealot deals 8 dmg → 3 absorbed by shields, 5 overflow to HP (45→40).
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.0f, 9.3f));
        game.setShieldsForTesting("probe-0", 3);
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(0);
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE) - 5); // 45 - 5 = 40
        // Zealot damagePerAttack=8: 3 shields absorb, 5 overflow to HP
    }

    @Test
    void unitDiesWhenHpReachesZero() {
        // probe-0 at 3 HP, 0 shields. Zealot at (9.0,9.3) — probe-0 nearest (0.3 tiles).
        // Zealot deals 8 dmg → HP goes to -5 → unit removed.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.0f, 9.3f));
        game.setHealthForTesting("probe-0", 3);
        game.setShieldsForTesting("probe-0", 0);
        int before = game.snapshot().myUnits().size();

        game.tick();

        assertThat(game.snapshot().myUnits()).hasSize(before - 1);
        assertThat(game.snapshot().myUnits().stream()
            .anyMatch(u -> u.tag().equals("probe-0"))).isFalse();
    }

    @Test
    void unitOutsideAttackRangeNotDamaged() {
        // Zealot melee range = 0.5 tiles. Place at 1.5 tiles away → out of range.
        // probe-0 is at (9,9). Enemy at (10.5,9) → distance=1.5 > 0.5 → no attack.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10.5f, 9));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(SC2Data.maxShields(UnitType.PROBE)); // untouched
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE));   // untouched
    }

    @Test
    void unitInsideAttackRangeReceivesDamage() {
        // Stalker range = 5 tiles. Place at 3 tiles away from probe-0 → in range.
        // probe-0 at (9,9). Stalker placed at (6,9) → distance=3 ≤ 5 → attacks.
        // Stalker range = 5 tiles. Stalker at (6,9). Probes 0–4 are in range (distances 3.0–5.0),
        // but probe-0 at distance 3.0 is the nearest so it is the sole target.
        // Stalker deals 13 dmg → probe-0 shields: 20→7.
        game.spawnEnemyForTesting(UnitType.STALKER, new Point2d(6f, 9));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(
            SC2Data.maxShields(UnitType.PROBE) - SC2Data.damagePerAttack(UnitType.STALKER));
        // 20 - 13 = 7
    }

    @Test
    void combatIsSimultaneous() {
        // probe-0 gets AttackIntent toward enemy → probe attacks enemy too.
        // probe-0 at 5 HP, 0 shields → Zealot's 8 dmg kills it.
        // Zealot has 50 shields; probe deals 4 dmg (5 raw - 1 armour) → shields drop 50→46 (simultaneous resolution).
        // Both damage computations happen before either is applied (simultaneous).
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.setHealthForTesting("probe-0", 5);
        game.setShieldsForTesting("probe-0", 0);

        game.tick();

        // probe-0 should be dead (5 HP - 8 dmg → health ≤ 0)
        assertThat(game.snapshot().myUnits().stream()
            .anyMatch(u -> u.tag().equals("probe-0"))).isFalse();
        // Enemy should still be alive but damaged (shields 50→46 from probe's 4 effective dmg)
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
        assertThat(game.snapshot().enemyUnits().get(0).shields())
            .isLessThan(SC2Data.maxShields(UnitType.ZEALOT));
    }

    // ---- E4: attack cooldowns ----

    @Test
    void firstAttackFiresImmediately() {
        // Initial cooldown = 0 (absent from map) — attack fires on first tick.
        // Probe vs Zealot (1 armour): effective = 5 - 1 = 4. Shields: 50 -> 46.
        // Zealot at (6.0, 9): probe-0 at (9,9) is exactly 3.0 tiles away (within range=3.0).
        // probe-1 at (9.5,9) is 3.5 tiles away — out of range. Only probe-0 fires.
        game.applyIntent(new AttackIntent("probe-0", new Point2d(6.0f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(6.0f, 9));

        game.tick();

        int effective = SC2Data.damagePerAttack(UnitType.PROBE) - SC2Data.armour(UnitType.ZEALOT); // 4
        Unit zealot = game.snapshot().enemyUnits().get(0);
        assertThat(zealot.shields())
            .isLessThan(SC2Data.maxShields(UnitType.ZEALOT))
            .isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - effective); // 46
    }

    @Test
    void attackCooldownPreventsRepeatOnNextTick() {
        // After attack fires, probe cooldown resets to 2 — no damage on tick 2
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick(); // tick 1: probe fires, cooldown → 2
        int shieldsAfterTick1 = game.snapshot().enemyUnits().get(0).shields(); // 46

        game.tick(); // tick 2: cooldown = 1, probe does NOT fire
        int shieldsAfterTick2 = game.snapshot().enemyUnits().get(0).shields();

        assertThat(shieldsAfterTick2).isEqualTo(shieldsAfterTick1);
    }

    @Test
    void cooldownExpiresAndAttackFiresAgain() {
        // PROBE cooldown = 2: fires tick 1, skips tick 2, fires tick 3.
        // Probe vs Zealot (1 armour): effective = 5 - 1 = 4 per hit. Shields: 50 -> 46 -> 42.
        // Zealot at (6.0,9): probe-0 at (9,9) is exactly 3.0 tiles away (within range=3.0).
        // probe-1 at (9.5,9) is 3.5 tiles away — out of range.
        // Zealot target set to (3,9) so it moves away from all probes (not toward nexus at 8,8).
        game.applyIntent(new AttackIntent("probe-0", new Point2d(6.0f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(6.0f, 9));
        // Override target so Zealot retreats away from probes — prevents probe-1 drifting into range.
        String zealotTag = game.enemy.units.get(game.enemy.units.size() - 1).tag();
        game.enemy.unitTargets.put(zealotTag, new Point2d(3f, 9f));

        game.tick(); // tick 1: attack (shields 50 -> 46)
        game.tick(); // tick 2: cooldown 1 - no attack; Zealot moving away
        game.tick(); // tick 3: cooldown 0 - attack fires again (46 -> 42)

        int effective = SC2Data.damagePerAttack(UnitType.PROBE) - SC2Data.armour(UnitType.ZEALOT); // 4
        Unit zealot = game.snapshot().enemyUnits().get(0);
        assertThat(zealot.shields())
            .isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - 2 * effective); // 42
    }

    @Test
    void moveIntentDoesNotPreventAutoAttack() {
        // Units auto-engage any enemy in range regardless of intent.
        // Probe-0 at (9,9), Zealot at (6.0,9) — only probe-0 in range (distance=3.0).
        // MoveIntent is issued to probe-0 but it still auto-attacks the Zealot while moving.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(6.0f, 9));
        game.applyIntent(new MoveIntent("probe-0", new Point2d(6.0f, 9))); // move toward enemy

        game.tick(); // probe-0 fires despite only having a MoveIntent

        Unit zealot = game.snapshot().enemyUnits().get(0);
        // Zealot shields must have dropped — auto-attack fired
        assertThat(zealot.shields()).isLessThan(SC2Data.maxShields(UnitType.ZEALOT));
    }

    @Test
    void enemyAlwaysAttacksWithCooldown() {
        // Enemy Zealot (cooldown=2) attacks probe every 2 ticks without AttackIntent.
        // Placed at (9.0,9.3) so probe-0 is the nearest target (0.3 tiles, probe-1 is 0.58 — out of range).
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.0f, 9.3f));

        game.tick(); // tick 1: Zealot fires (cooldown 0→2), probe shields: 20→12
        int shieldsAfterTick1 = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow().shields();
        assertThat(shieldsAfterTick1)
            .isEqualTo(SC2Data.maxShields(UnitType.PROBE) - SC2Data.damagePerAttack(UnitType.ZEALOT));
        // 20 - 8 = 12

        game.tick(); // tick 2: Zealot cooldown 1 — no attack. shields unchanged.
        int shieldsAfterTick2 = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow().shields();
        assertThat(shieldsAfterTick2).isEqualTo(shieldsAfterTick1);
    }

    // ---- E4: enemy economy ----
    // Tests removed — EnemyStrategy is now an interface; the old record-based economy
    // implementation (buildOrder/loop) has been gutted. Economy tests will be restored
    // in Task 6 (Refactor EmulatedGame with EnemyBehavior + FixedBuildOrderStrategy).

    @Test
    void enemyStrategyNullIsNoop() {
        game.setEnemyStrategy(null);
        game.tick(); // must not throw NullPointerException
        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
    }

    // ---- E5: damage types, armour, Hardened Shield ----

    @Test
    void stalkerDealsCorrectDamageVsArmored() {
        // Stalker (friendly) attacks Roach (Armored, 1 armour):
        // effective = 13 + 4 (vs Armored) - 1 = 16, not raw 13
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.ROACH, new Point2d(3, 5)); // distance=2.0 <= Stalker range 5.0
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit roach = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ROACH)
            .findFirst().orElseThrow();
        assertThat(roach.health()).isEqualTo(SC2Data.maxHealth(UnitType.ROACH) - 16); // 145 - 16 = 129
    }

    @Test
    void armourReducesIncomingDamage() {
        // Stalker (friendly) attacks Zealot (LIGHT, 1 armour):
        // effective = 13 + 0 (no bonus vs Light) - 1 = 12, not raw 13
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(3, 5));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit zealot = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        // Without armour: shields = 50 - 13 = 37. With armour: 50 - 12 = 38.
        assertThat(zealot.shields()).isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - 12); // 38
    }

    @Test
    void immortalShieldedCapsDamageAt10() {
        // Stalker (friendly) attacks shielded Immortal:
        // effective before cap = 13+4-1=16, Hardened Shield -> min(10,16) = 10
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.IMMORTAL, new Point2d(3, 5));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit immortal = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.IMMORTAL)
            .findFirst().orElseThrow();
        assertThat(immortal.shields()).isEqualTo(SC2Data.maxShields(UnitType.IMMORTAL) - 10); // 90
        assertThat(immortal.health()).isEqualTo(SC2Data.maxHealth(UnitType.IMMORTAL));         // 200 untouched
    }

    @Test
    void immortalUnshieldedTakesFullDamage() {
        // Stalker vs Immortal with 0 shields: no Hardened Shield -> full 16 damage to HP
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.IMMORTAL, new Point2d(3, 5));
        String immortalTag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyShieldsForTesting(immortalTag, 0);
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit immortal = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.IMMORTAL)
            .findFirst().orElseThrow();
        assertThat(immortal.health()).isEqualTo(SC2Data.maxHealth(UnitType.IMMORTAL) - 16); // 184
    }

    @Test
    void spawnedMarineHasCorrectHp() {
        game.spawnEnemyForTesting(UnitType.MARINE, new Point2d(50, 50)); // far from Probes - no combat
        Unit marine = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.MARINE)
            .findFirst().orElseThrow();
        assertThat(marine.health()).isEqualTo(45);
    }

    @Test
    void spawnedImmortalHasCorrectHp() {
        game.spawnEnemyForTesting(UnitType.IMMORTAL, new Point2d(50, 50));
        Unit immortal = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.IMMORTAL)
            .findFirst().orElseThrow();
        assertThat(immortal.health()).isEqualTo(200);
    }

    // ---- E6: retreat infrastructure ----

    @Test
    void retreatingUnitTagsIsInitiallyEmpty() {
        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    // ---- E6: retreat logic ----

    @Test
    void lowHealthUnitRetreats() {
        // Zealot HP+shields = 1+0 / 150 = 0.7% — below retreatHealthPercent=30
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick();

        assertThat(game.retreatingUnitTags()).contains(tag);
    }

    @Test
    void healthyUnitDoesNotRetreat() {
        // Zealot at full HP+shields = 150/150 = 100% — well above retreatHealthPercent=30
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        game.setInitialAttackSizeForTesting(1);

        game.tick();

        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    @Test
    void armyDepletionTriggersGroupRetreat() {
        // 1 unit alive of 4 launched = 25% — below retreatArmyPercent=50
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 0, 50);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setInitialAttackSizeForTesting(4); // 1 alive of 4 launched = 25% < 50%

        game.tick();

        assertThat(game.retreatingUnitTags()).contains(tag);
    }

    @Test
    void retreatingUnitMovesTowardStaging() {
        // Tick 1: retreat fires → target becomes STAGING_POS (26,26)
        // Tick 2: unit moves toward staging — measurably closer than after tick 1
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick(); // retreat fires; unit moved toward nexus first, now target = staging
        Point2d afterTick1 = game.snapshot().enemyUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow().position();

        game.tick(); // unit moves toward staging
        Point2d afterTick2 = game.snapshot().enemyUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow().position();

        double distBefore = EmulatedGame.distance(afterTick1, new Point2d(26, 26));
        double distAfter  = EmulatedGame.distance(afterTick2, new Point2d(26, 26));
        assertThat(distAfter).isLessThan(distBefore);
    }

    @Test
    void retreatingUnitTransfersToStagingOnArrival() {
        // Unit placed exactly at STAGING_POS.
        // Tick 1: moveEnemyUnits moves it ~0.5 tiles toward nexus. retreat fires.
        //         Transfer check: distance ~0.5 >= 0.1 → NOT yet transferred.
        // Tick 2: moveEnemyUnits moves it back toward STAGING_POS (dist ~0.5 = speed → snaps to (26,26)).
        //         Transfer check: distance = 0 < 0.1 → TRANSFERRED.
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(26, 26)); // at staging
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick(); // retreat fires; not yet transferred
        assertThat(game.retreatingUnitTags()).contains(tag);

        game.tick(); // unit snaps to staging; transfer fires
        assertThat(game.snapshot().enemyUnits().stream()
            .anyMatch(u -> u.tag().equals(tag))).isFalse();
        assertThat(game.snapshot().enemyStagingArea()).hasSize(1);
        assertThat(game.snapshot().enemyStagingArea().get(0).tag()).isEqualTo(tag);
    }

    @Test
    void retreatedUnitKeepsDamagedHp() {
        // Zealot: 40+0 / 150 = 26.7% < 30% → retreats. HP preserved at 40 in staging.
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(26, 26)); // at staging
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 40);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick(); // retreat fires; not yet transferred (moved toward nexus)
        game.tick(); // snaps back to staging; transfer fires

        assertThat(game.snapshot().enemyStagingArea()).hasSize(1);
        assertThat(game.snapshot().enemyStagingArea().get(0).health()).isEqualTo(40);
    }

    @Test
    void disabledThresholdsNeverRetreat() {
        // Both thresholds = 0 → no retreat regardless of HP
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 0, 0);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick();

        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    @Test
    void retreatDoesNotFireBeforeFirstAttack() {
        // initialAttackSize = 0 (no wave launched) — guard prevents any retreat
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 50);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        // DO NOT call setInitialAttackSizeForTesting — leave at 0

        game.tick();

        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    @Test
    void deadUnitRemovedFromRetreatingSet() {
        // Probe vs Zealot: 5−1(armour)=4 effective damage per probe hit.
        // Tick 1: probe-0 hits, Zealot HP=9-4=5; retreat fires (5+0)/(150+50)=2.5%<30%. In retreatingUnits.
        //         Zealot enters retreat mode (target → STAGING_POS) and begins moving away.
        // Tick 2: probe-0 cooldown=1, no fire. As Zealot retreats toward (26,26) it drifts into
        //         probe-1's range; probe-1 fires: HP=5-4=1. Zealot still alive, still retreating.
        // Tick 3: probe-0 fires again. Zealot HP=1-4<0, dies. resolveCombat cleans retreatingUnits.
        // HP=9 chosen so Zealot needs exactly 3 probe hits to die (9, 5, 1, dead).
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(6.0f, 9));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 9);
        game.setEnemyShieldsForTesting(tag, 0); // 9/200=4.5% < 30% — retreats on first hit
        game.setInitialAttackSizeForTesting(1);
        game.applyIntent(new AttackIntent("probe-0", new Point2d(6.0f, 9)));

        game.tick(); // probe-0 hits: HP=5; retreat fires → Zealot in retreatingUnits
        assertThat(game.retreatingUnitTags()).contains(tag);

        game.tick(); // probe-1 enters range as Zealot retreats; hits: HP=1. Zealot alive, still retreating
        assertThat(game.retreatingUnitTags()).contains(tag);

        game.tick(); // probe-0 fires → HP=1-4<0, dies; resolveCombat clears retreatingUnits
        assertThat(game.retreatingUnitTags()).doesNotContain(tag);
        assertThat(game.snapshot().enemyUnits().stream()
            .anyMatch(u -> u.tag().equals(tag))).isFalse();
    }

    @Test
    void alreadyRetreatingUnitSkippedByArmyCheck() {
        // Per-unit threshold fires first → unit added to retreatingUnits with target = STAGING_POS.
        // Army-wide check then fires (1/4 = 25% < 50%). The already-retreating unit must be
        // skipped — its target must NOT be overwritten, and it must not be double-added.
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 50);
        game.setEnemyStrategy(retreatStrategy(atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0); // 1/150 < 30% → per-unit fires
        game.setInitialAttackSizeForTesting(4); // 1/4 = 25% < 50% → army check also fires

        game.tick();

        // Unit must be in retreatingUnits exactly once (Set guarantees this)
        // and target must be STAGING_POS (set by per-unit check, not overwritten by army check)
        assertThat(game.retreatingUnitTags()).containsExactly(tag);
        // enemyUnits still contains the unit (not yet arrived at staging)
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
    }

    // ---- E7: pathfinding integration ----

    @Test
    void withPathfinding_unitEventuallyReachesTargetAcrossWall() {
        game.setMovementStrategy(new PathfindingMovement(TerrainGrid.emulatedMap()));
        // From nexus side (8,8) to staging side (12,22) — must cross wall at y=18 via chokepoint
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(8, 8));
        game.applyIntent(new MoveIntent(tag, new Point2d(12, 22)));
        // ~30 tiles / 0.5 per tick = ~60 ticks; use 120 for safety
        for (int i = 0; i < 120; i++) game.tick();
        Unit unit = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        assertThat(EmulatedGame.distance(unit.position(), new Point2d(12, 22))).isLessThan(2.0);
    }

    @Test
    void withPathfinding_unitDoesNotCrossWallOutsideChokepoint() {
        game.setMovementStrategy(new PathfindingMovement(TerrainGrid.emulatedMap()));
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(8, 8));
        game.applyIntent(new MoveIntent(tag, new Point2d(12, 22)));
        for (int i = 0; i < 80; i++) {
            game.tick();
            game.snapshot().myUnits().stream()
                .filter(u -> u.tag().equals(tag)).findFirst().ifPresent(unit -> {
                    int tileX = (int) unit.position().x();
                    int tileY = (int) unit.position().y();
                    if (tileY == 18) {
                        assertThat(tileX)
                            .as("unit at y=18 must be in gap x=[11,13], was x=%d", tileX)
                            .isBetween(11, 13);
                    }
                });
        }
    }

    @Test
    void wallPhysicsBlocksUnitRegardlessOfMovementStrategy() {
        // Hard physics rule: even if the movement strategy returns a wall tile position,
        // enforceWall() must reject it and hold the unit. This is independent of pathfinding.
        // Place a unit adjacent to the wall (y=17.6) heading toward a target above it (y=22).
        // DirectMovement would step into y=18 (wall tile) — the physics constraint must block it.
        // x=20 is a wall tile at y=18 (gap is only x=11-13). x=12 would be the gap — use x=20.
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        // DirectMovement is the default — it ignores walls
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(20f, 17.6f));
        game.applyIntent(new MoveIntent(tag, new Point2d(20f, 22f)));

        game.tick(); // DirectMovement would move to (12, 18.1) — a wall tile

        Unit unit = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        // Physics must have held the unit below the wall — y must remain < 18
        assertThat(unit.position().y())
            .as("unit must not enter wall tile at y=18 — physics constraint must hold it")
            .isLessThan(18f);
    }

    @Test
    void enemyRespectsWallWithPathfinding() {
        // Wall at y=18, gap at x=11-13. Enemy spawns at (26,26) and heads to nexus (8,8).
        // DirectMovement would cross y=18 at x≈26 (wall tile) — the live bug.
        // With PathfindingMovement the unit must stay within x=[11,13] when at y=18.
        TerrainGrid terrain = TerrainGrid.emulatedMap();
        game.setMovementStrategy(new PathfindingMovement(terrain));
        game.setTerrainGrid(terrain); // enables enforceWall() — unit can't cross wall tiles during stepToward
        game.configureWave(1, 1, UnitType.ZEALOT);
        game.reset();
        game.tick(); // frame 1: wave spawns at (26,26)
        for (int i = 0; i < 80; i++) {
            game.tick();
            game.snapshot().enemyUnits().stream().findFirst().ifPresent(u -> {
                int tileX = (int) u.position().x();
                int tileY = (int) u.position().y();
                if (tileY == 18) {
                    assertThat(tileX)
                        .as("enemy at y=18 must be in chokepoint gap x=[11,13], was x=%d", tileX)
                        .isBetween(11, 13);
                }
            });
        }
    }

    @Test
    void enemyAdvancesEvenWhileFighting() {
        // Zealot melee range = 0.5 tiles. Placed at (9.0,9.3) — probe-0 at distance 0.3 (in range).
        // After stop-to-fight removal: Zealot advances toward nexus each tick even while engaging.
        // It attacks probe-0 AND moves toward (8,8) in the same tick.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.0f, 9.3f));
        Point2d before = game.snapshot().enemyUnits().get(0).position();

        game.tick();

        Unit zealot = game.enemy.units.stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        Point2d after = zealot.position();
        assertThat(after).as("enemy must have moved — stop-to-fight removed").isNotEqualTo(before);
        // And the probe also took damage — combat still fires while moving
        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).as("probe-0 shields must drop — Zealot attacked while moving").isLessThan(SC2Data.maxShields(UnitType.PROBE));
    }

    @Test
    void enemyMovesWhenNoFriendlyInRange() {
        // Zealot spawned at (20,20) — nearest probe is at (14.5,9), distance ≈12 tiles > 0.5 range.
        // No friendly in melee range → enemy must advance toward nexus each tick.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(20f, 20f));
        Point2d before = game.snapshot().enemyUnits().get(0).position();

        game.tick();

        Point2d after = game.snapshot().enemyUnits().get(0).position();
        assertThat(after.x()).as("enemy must move toward nexus (x decreases)").isLessThan(before.x());
        assertThat(after.y()).as("enemy must move toward nexus (y decreases)").isLessThan(before.y());
    }

    // ---- E11: TerrainAwareKiteStrategy physics ----

    @Test
    void terrainAwareKiting_doesNotStepIntoWallTile() {
        // Unit at (10,17), enemy at (10,15) — ideal kite direction is +y toward (10,18) = WALL.
        // TerrainAwareKiteStrategy must sweep to a walkable alternative.
        // We drive the kite manually: compute retreat via strategy, issue MoveIntent, tick.
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(10f, 17f));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10f, 15f));

        TerrainAwareKiteStrategy strategy = new TerrainAwareKiteStrategy();
        TerrainGrid terrain = TerrainGrid.emulatedMap();

        for (int i = 0; i < 8; i++) {
            GameState state = game.snapshot();
            Unit stalker = state.myUnits().stream()
                .filter(u -> u.tag().equals(tag)).findFirst().orElse(null);
            List<Unit> enemies = state.enemyUnits();
            if (stalker == null || enemies.isEmpty()) break;
            Point2d retreat = strategy.retreatTarget(stalker, enemies, terrain);
            game.applyIntent(new MoveIntent(tag, retreat));
            game.tick();
        }

        // No friendly unit should be on a WALL tile after kiting
        for (Unit u : game.snapshot().myUnits()) {
            assertThat(terrain.heightAt((int) u.position().x(), (int) u.position().y()))
                .as("unit %s ended up at %s which is a wall", u.tag(), u.position())
                .isNotEqualTo(TerrainGrid.Height.WALL);
        }
    }

    @Test
    void directMovementDefaultIsUnchanged() {
        // No setMovementStrategy call — defaults to DirectMovement (straight line)
        game.applyIntent(new MoveIntent("probe-0", new Point2d(15, 9)));
        Point2d before = game.snapshot().myUnits().get(0).position();
        game.tick();
        Point2d after = game.snapshot().myUnits().get(0).position();
        assertThat(after.x()).isGreaterThan(before.x());
        assertThat(after.y()).isCloseTo(before.y(), org.assertj.core.data.Offset.offset(0.1f));
    }

    // ---- E8: high-ground miss chance ----

    @Test
    void rangedAttackLowToHighMissesWhenRngSaysNo() {
        // Stalker (friendly, range=5) on LOW (y=14), enemy Zealot on HIGH (y=19).
        // Distance = 5 tiles = attack range → attack fires.
        // Always-miss RNG (nextDouble()=0.0 < 0.25) → every ranged low→high attack misses.
        // Observer on HIGH at (5,28) provides vision of (5,19): distance=9≤sight(10), HIGH→HIGH.
        // Observer is at distance 9 > stalker range 5 — it does NOT attack the Zealot.
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.setRandomForTesting(new java.util.Random() {
            @Override public double nextDouble() { return 0.0; } // always < 0.25 → miss
        });
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(5, 14));
        game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(5, 28)); // observer on HIGH, out of attack range
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(5, 19));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(5, 19)));

        game.tick(); // Stalker fires but misses — no damage to Zealot

        Unit zealot = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        assertThat(zealot.shields()).isEqualTo(SC2Data.maxShields(UnitType.ZEALOT)); // 50 — untouched
    }

    @Test
    void rangedAttackLowToHighHitsWhenRngSaysYes() {
        // Same positions, never-miss RNG (nextDouble()=1.0 ≥ 0.25) → attack lands.
        // Observer on HIGH at (5,28) provides vision of (5,19): distance=9≤sight(10), HIGH→HIGH.
        // Observer is at distance 9 > stalker range 5 — it does NOT attack the Zealot.
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.setRandomForTesting(new java.util.Random() {
            @Override public double nextDouble() { return 1.0; } // never < 0.25 → hit
        });
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(5, 14));
        game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(5, 28)); // observer on HIGH, out of attack range
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(5, 19));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(5, 19)));

        game.tick();

        Unit zealot = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        // Only the LOW→HIGH stalker fires (observer at y=28 is out of range).
        // Stalker vs Zealot: 13 base + 0 bonus (Zealot is LIGHT not ARMORED) - 1 armour = 12
        assertThat(zealot.shields()).isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - 12); // 38
    }

    @Test
    void meleeAttackLowToHighNeverMisses() {
        // Zealot (friendly, range=0.5 ≤ 1.0 → melee) should never invoke the miss check.
        // Custom 2-tile TerrainGrid: tile(0,0)=LOW, tile(1,0)=HIGH.
        // Zealot at (0.6, 0) → floor → tile(0,0)=LOW.
        // Enemy Marine at (1.1, 0) → floor → tile(1,0)=HIGH.
        // Distance = 0.5 tiles ≤ Zealot range 0.5 → attack fires.
        // Always-miss RNG → melee must still deal full damage (range check skips the miss roll).
        // Marine target is set toward the Zealot (not the Nexus) so it doesn't walk out of range.
        TerrainGrid.Height[][] heights = new TerrainGrid.Height[2][1];
        heights[0][0] = TerrainGrid.Height.LOW;
        heights[1][0] = TerrainGrid.Height.HIGH;
        game.setTerrainGrid(new TerrainGrid(2, 1, heights));
        game.setRandomForTesting(new java.util.Random() {
            @Override public double nextDouble() { return 0.0; } // always-miss — must be ignored for melee
        });
        String zealotTag = game.spawnFriendlyForTesting(UnitType.ZEALOT, new Point2d(0.6f, 0));
        game.spawnEnemyForTesting(UnitType.MARINE, new Point2d(1.1f, 0));
        // Override Marine's default target (Nexus) to point toward the Zealot so it stays in range.
        // Access enemy.units directly (same package) — snapshot() filters by fog before any tick.
        String marineTag = game.enemy.units.get(game.enemy.units.size() - 1).tag();
        game.enemy.unitTargets.put(marineTag, new Point2d(0.6f, 0f));
        game.applyIntent(new AttackIntent(zealotTag, new Point2d(1.1f, 0)));

        game.tick();

        Unit marine = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.MARINE)
            .findFirst().orElseThrow();
        // Zealot vs Marine: 8 base + 0 bonus - 0 armour = 8. Marine max shields = 0, HP 45 → 37.
        assertThat(marine.health()).isEqualTo(SC2Data.maxHealth(UnitType.MARINE) - 8); // 37
    }

    @Test
    void rangedAttackEqualHeightNeverMisses() {
        // Both attacker and target on LOW — no miss check regardless of RNG.
        // Stalker (LOW, y=9) vs enemy Zealot (LOW, y=14). Distance = 5 = range.
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.setRandomForTesting(new java.util.Random() {
            @Override public double nextDouble() { return 0.0; } // would always miss if check fires
        });
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(5, 9));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(5, 14));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(5, 14)));

        game.tick();

        Unit zealot = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        assertThat(zealot.shields()).isLessThan(SC2Data.maxShields(UnitType.ZEALOT)); // took damage
    }

    @Test
    void rangedAttackHighToLowNeverMisses() {
        // Enemy Stalker on HIGH (y=19) attacks friendly on LOW (y=14). No miss — shooting downhill.
        // Distance from (5,19) to (5,14) = 5 ≤ Stalker range 5 → attacks fire.
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.setRandomForTesting(new java.util.Random() {
            @Override public double nextDouble() { return 0.0; } // would miss if check fires
        });
        game.spawnEnemyForTesting(UnitType.STALKER, new Point2d(5, 19));
        game.spawnFriendlyForTesting(UnitType.PROBE, new Point2d(5, 14));

        game.tick(); // enemy Stalker auto-attacks nearest friendly — no miss check for high→low

        // The newly spawned probe (not probe-0 at y=9) should take damage.
        // probe-0 is at (9,9) — distance to enemy (5,19) ≈ 10.8 > 5. Out of range.
        // test-unit-N is at (5,14) — distance 5 ≤ range 5. Takes damage.
        boolean anyProbeDamaged = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE)
            .anyMatch(u -> u.shields() < SC2Data.maxShields(UnitType.PROBE));
        assertThat(anyProbeDamaged).isTrue();
    }

    @Test
    void rampAttackerDoesNotTriggerMissChance() {
        // Stalker (friendly) on RAMP tile — RAMP ≠ LOW → no miss check even with always-miss RNG.
        // RAMP tiles: x=11-13, y=18. Position (12.5, 18.5) → floor → tile(12,18) = RAMP.
        // Enemy Zealot at (12.5, 19.5) → tile(12,19) = HIGH. Distance ≈ 1 ≤ Stalker range 5.
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.setRandomForTesting(new java.util.Random() {
            @Override public double nextDouble() { return 0.0; } // always-miss if check fires
        });
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(12.5f, 18.5f));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(12.5f, 19.5f));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(12.5f, 19.5f)));

        game.tick();

        Unit zealot = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        assertThat(zealot.shields()).isLessThan(SC2Data.maxShields(UnitType.ZEALOT)); // took damage
    }

    // ---- E9: Fog of War filtering ----

    @Test
    void enemyOnHighGroundIsInvisibleFromLow() {
        // Emulated map: nexus at (8,8)=LOW, enemy staging at (26,26)=HIGH
        // Friendly units are on LOW — they cannot see HIGH ground
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.reset();
        // Spawn enemy on HIGH ground (y=25 > 18)
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(26, 25));
        game.tick();
        // snapshot() should filter out the HIGH-ground enemy
        assertThat(game.snapshot().enemyUnits()).isEmpty();
    }

    @Test
    void enemyOnLowGroundIsVisibleFromLow() {
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.reset();
        // Spawn enemy on LOW ground adjacent to our units (y=10 < 18), within sight range
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(12, 10));
        game.tick();
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
    }

    @Test
    void enemyOnHighRemainsHiddenAcrossMultipleTicks() {
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.reset();
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(26, 25));

        // Run several ticks — probes stay on LOW, enemy on HIGH stays invisible
        for (int i = 0; i < 10; i++) game.tick();
        assertThat(game.snapshot().enemyUnits())
            .as("HIGH-ground enemy must stay hidden while all observers are on LOW")
            .isEmpty();
    }

    @Test
    void stagingAreaEnemiesAreFilteredByVisibility() {
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.reset();
        // addStagedUnitForTesting places a unit in enemyStagingArea at STAGING_POS (26,26) = HIGH
        game.addStagedUnitForTesting(UnitType.ZEALOT, new Point2d(26, 26));
        game.tick();
        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
    }

    @Test
    void visibilityGridResetOnGameReset() {
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.reset();
        game.tick(); // populates some VISIBLE tiles
        game.reset();
        // After reset, all tiles should be UNSEEN again — confirmed via observeVisibility()
        VisibilityGrid vg = game.observeVisibility();
        assertThat(vg.at(8, 8)).isEqualTo(TileVisibility.UNSEEN);
    }

    // ---- E10: weapon cooldown snapshot ----

    @Test
    void firingUnit_hasCooldownInSnapshot() {
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(10, 10));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10, 12)); // within Stalker range 5.0
        game.applyIntent(new AttackIntent(tag, new Point2d(10, 12)));
        game.tick(); // Stalker fires; cooldown = SC2Data.attackCooldownInTicks(STALKER) = 3
        Unit stalker = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        assertThat(stalker.weaponCooldownTicks())
            .isEqualTo(SC2Data.attackCooldownInTicks(UnitType.STALKER));
    }

    @Test
    void freshUnit_hasCooldownZeroInSnapshot() {
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(10, 10));
        Unit stalker = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        assertThat(stalker.weaponCooldownTicks()).isEqualTo(0);
    }

    // ---- E10: Auto-attack targeting ----

    @Test
    void autoAttack_prefersLowerHpAtEqualDistance() {
        // With auto-engage, nearestInRange picks the nearest enemy; at equal distance it
        // prefers lower HP+shields (natural focus fire). Two Zealots equidistant from a Stalker,
        // one wounded — the Stalker always attacks the wounded one.
        game.reset();
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(10, 10));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(12, 10)); // zealot-0: full HP
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(12, 10)); // zealot-1: same pos, wounded
        // Wound the second zealot so its HP+shields total is lower
        String woundedTag = game.snapshot().enemyUnits().get(1).tag();
        game.enemy.units.replaceAll(u -> u.tag().equals(woundedTag)
            ? new Unit(u.tag(), u.type(), u.position(), 10, u.maxHealth(), 0, u.maxShields(), 0, 0)
            : u);

        game.tick();

        // The wounded zealot (lower HP+shields) should have taken damage (or died)
        boolean woundedTookDamage = game.enemy.units.stream()
            .noneMatch(u -> u.tag().equals(woundedTag))  // died
            || game.enemy.units.stream()
                .filter(u -> u.tag().equals(woundedTag))
                .anyMatch(u -> u.health() < 10);
        assertThat(woundedTookDamage).as("auto-attack must target lower-HP enemy first").isTrue();
    }

    // ---- E12 blink tests ----

    @Test
    void stalkerThatBlinksRetainsMoreHpThanNonBlinkingControl() {
        // Run A: Stalker blinks when shields critically low — blink teleports it away and
        //        restores 40 shields, breaking melee contact with the Zealot.
        // Run B: same scenario, no blink — Stalker stays in melee and keeps taking hits.
        // Pre-condition: Stalker shields set to 25 (just above 25% threshold of 80 = 20).
        //   First Zealot hit (8 - 1 armour = 7 effective) drops shields to 18 < 20 → triggers blink.

        // ----- Run A: with blink -----
        EmulatedGame runA = new EmulatedGame();
        runA.configureWave(9999, 1, UnitType.ZEALOT);
        runA.reset();
        String stalkerTagA = runA.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(10.0f, 10.0f));
        runA.setShieldsForTesting(stalkerTagA, 25); // near threshold: first hit drops to 18 < 20
        runA.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10.0f, 10.3f));

        boolean blinkUsedA = false;
        for (int t = 0; t < 30; t++) {
            List<Unit> enemiesA = runA.snapshot().enemyUnits();
            if (enemiesA.isEmpty()) break;
            Unit stalkerA = runA.snapshot().myUnits().stream()
                .filter(u -> u.tag().equals(stalkerTagA)).findFirst().orElse(null);
            if (stalkerA == null) break;
            if (!blinkUsedA && stalkerA.shields() < stalkerA.maxShields() * 0.25
                    && stalkerA.blinkCooldownTicks() == 0) {
                runA.applyIntent(new BlinkIntent(stalkerTagA));
                blinkUsedA = true;
            } else {
                runA.applyIntent(new AttackIntent(stalkerTagA, enemiesA.get(0).position()));
            }
            runA.tick();
        }

        // ----- Run B: no blink -----
        EmulatedGame runB = new EmulatedGame();
        runB.configureWave(9999, 1, UnitType.ZEALOT);
        runB.reset();
        String stalkerTagB = runB.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(10.0f, 10.0f));
        runB.setShieldsForTesting(stalkerTagB, 25); // same starting state
        runB.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10.0f, 10.3f));

        for (int t = 0; t < 30; t++) {
            List<Unit> enemiesB = runB.snapshot().enemyUnits();
            if (enemiesB.isEmpty()) break;
            Unit stalkerB = runB.snapshot().myUnits().stream()
                .filter(u -> u.tag().equals(stalkerTagB)).findFirst().orElse(null);
            if (stalkerB == null) break;
            runB.applyIntent(new AttackIntent(stalkerTagB, enemiesB.get(0).position()));
            runB.tick();
        }

        int hpA = runA.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(stalkerTagA))
            .mapToInt(u -> u.health() + u.shields()).findFirst().orElse(0);
        int hpB = runB.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(stalkerTagB))
            .mapToInt(u -> u.health() + u.shields()).findFirst().orElse(0);

        assertThat(blinkUsedA).as("blink should have triggered in run A").isTrue();
        assertThat(hpA).as("blinking Stalker should survive with more total HP+shields")
            .isGreaterThan(hpB);
    }

    // ---- E10: Kiting physics ----

    // At STALKER cooldown=1, kiting fires every other tick:
    //   attack tick  → AttackIntent fires the weapon, cooldown set to 1
    //   cooldown tick → MoveIntent(retreat) clears attackingUnits so no fire this tick;
    //                   Stalker steps 0.5 tiles away from the approaching Zealot
    //
    // The baseline is "standing-still": same every-other-tick fire rate (achieved by
    // issuing MoveIntent(own position) on cooldown ticks to also clear attackingUnits),
    // but the Stalker never retreats.  This isolates the retreat movement as the only
    // variable.
    //
    // Starting setup: Stalker at (8,12), Zealot at (8,16), distance = 4.0 tiles.
    // Standing:  Zealot closes 0.5/tick (unimpeded) → melee contact in ~6 ticks.
    // Kiting:    on cooldown ticks Stalker retreats 0.5 while Zealot advances 0.5 →
    //            net separation unchanged that tick → melee contact doubled to ~12 ticks.
    // Result: kiting Stalker receives far fewer Zealot attacks → significantly more HP.

    @Test
    void kiting_stallsEnemyContact_stalkerRetainsMoreHp() {
        int standingHp = runStandingStillScenario();
        int kitingHp   = runKitingScenario();
        assertThat(kitingHp).isGreaterThan(standingHp);
    }

    /**
     * Baseline: same every-other-tick fire rate as kiting, but Stalker never retreats.
     * MoveIntent(own position) on cooldown ticks clears attackingUnits (matching kiting's
     * fire parity).  The Zealot closes at full speed (0.5 tiles/tick) and reaches melee
     * range sooner — generating more Zealot attacks before the Stalker can kill it.
     */
    private int runStandingStillScenario() {
        game.reset();
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(8, 12));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(8, 16));
        for (int tick = 0; tick < 25; tick++) {
            GameState state = game.snapshot();
            Unit stalker = state.myUnits().stream()
                .filter(u -> u.tag().equals(tag)).findFirst().orElse(null);
            List<Unit> enemies = state.enemyUnits();
            if (stalker == null || enemies.isEmpty()) break;
            if (stalker.weaponCooldownTicks() == 0) {
                // Off cooldown: attack in place — own position as target so Stalker does not move
                game.applyIntent(new AttackIntent(tag, stalker.position()));
            } else {
                // On cooldown: idle in place — MoveIntent(own pos) clears attackingUnits,
                // giving the same every-other-tick fire rate as the kiting scenario
                game.applyIntent(new MoveIntent(tag, stalker.position()));
            }
            game.tick();
        }
        return game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag))
            .mapToInt(u -> u.health() + u.shields()).findFirst().orElse(0);
    }

    /**
     * Kiting: Stalker attacks in place when cooldown is 0, retreats 1 tile away from
     * the Zealot on cooldown ticks.  MoveIntent on cooldown ticks clears attackingUnits
     * (same fire rate as standing), but the retreat delays the Zealot reaching melee —
     * resulting in significantly fewer Zealot attacks and more surviving HP.
     */
    private int runKitingScenario() {
        game.reset();
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(8, 12));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(8, 16));
        for (int tick = 0; tick < 25; tick++) {
            GameState state = game.snapshot();
            Unit stalker = state.myUnits().stream()
                .filter(u -> u.tag().equals(tag)).findFirst().orElse(null);
            List<Unit> enemies = state.enemyUnits();
            if (stalker == null || enemies.isEmpty()) break;
            if (stalker.weaponCooldownTicks() > 0) {
                // On cooldown: kite backward (away from nearest enemy)
                Unit nearest = enemies.stream()
                    .min(Comparator.comparingDouble(e ->
                        Math.sqrt(Math.pow(e.position().x() - stalker.position().x(), 2) +
                                  Math.pow(e.position().y() - stalker.position().y(), 2))))
                    .orElseThrow();
                double dx = stalker.position().x() - nearest.position().x();
                double dy = stalker.position().y() - nearest.position().y();
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len > 0.001) {
                    Point2d retreat = new Point2d(
                        (float)(stalker.position().x() + dx / len),
                        (float)(stalker.position().y() + dy / len));
                    game.applyIntent(new MoveIntent(tag, retreat));
                }
            } else {
                // Off cooldown: attack in place (own position as target — no movement toward
                // enemy, so only the retreat on cooldown ticks differentiates the scenarios)
                game.applyIntent(new AttackIntent(tag, stalker.position()));
            }
            game.tick();
        }
        return game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag))
            .mapToInt(u -> u.health() + u.shields()).findFirst().orElse(0);
    }

    // ---- Enemy production tests ----

    @Test
    void enemyBehavior_accumulatesMinerals() {
        game.setEnemyStrategy(new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT), 5, new EnemyAttackConfig(3, 200, 0, 0)));
        game.reset();
        double before = game.enemy.minerals;
        game.tick();
        assertThat(game.enemy.minerals).isGreaterThan(before);
    }

    // --- Building collision ---

    /**
     * Units must not walk through completed buildings.
     * A friendly Stalker heading directly into an enemy Hatchery must be blocked
     * at the building's collision radius — it must not reach or pass through the centre.
     */
    @Test
    void buildingCollisionBlocksFriendlyUnitFromEnteringEnemyBuilding() {
        game.setTerrainGrid(TerrainGrid.emulatedMap()); // enables enforceWall backstop
        // Enemy Hatchery at (20, 20) — well away from terrain wall
        final var hatcheryPos = new Point2d(20f, 20f);
        final float radius = SC2Data.buildingRadius(BuildingType.HATCHERY);
        game.enemy.buildings.add(new Building("test-hatchery", BuildingType.HATCHERY,
            hatcheryPos, 1500, 1500, true));
        // Friendly Stalker starts 4 tiles south, heading north through the Hatchery
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(20f, 16f));
        game.applyIntent(new MoveIntent(tag, new Point2d(20f, 25f)));
        for (int i = 0; i < 20; i++) game.tick();

        Unit unit = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        double dist = EmulatedGame.distance(unit.position(), hatcheryPos);
        assertThat(dist)
            .as("Stalker must stop outside Hatchery radius %.1f — was %.2f", radius, dist)
            .isGreaterThanOrEqualTo(radius - 0.1f);
    }

    /**
     * Enemy units must also be blocked by friendly buildings.
     * An enemy Zergling heading into the friendly Nexus at (8,8) must stop outside its radius.
     */
    @Test
    void buildingCollisionBlocksEnemyUnitFromEnteringFriendlyBuilding() {
        game.setTerrainGrid(TerrainGrid.emulatedMap());
        game.reset(); // Nexus is at (8,8) in friendly.buildings after reset
        final var nexusPos = new Point2d(8f, 8f);
        final float radius = SC2Data.buildingRadius(BuildingType.NEXUS);
        // Enemy Zergling starts 5 tiles north of Nexus, heading straight at it.
        // HP=1500 so it survives probe auto-attacks (probes enter range as it crosses y≈9).
        String tag = "test-enemy-bldg-coll";
        game.enemy.units.add(new Unit(tag, UnitType.ZERGLING, new Point2d(8f, 13f),
            1500, 1500, 0, 0, 0, 0));
        game.enemy.unitTargets.put(tag, nexusPos);
        for (int i = 0; i < 20; i++) game.tick();

        Unit unit = game.snapshot().enemyUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        double dist = EmulatedGame.distance(unit.position(), nexusPos);
        assertThat(dist)
            .as("Zergling must stop outside Nexus radius %.1f — was %.2f", radius, dist)
            .isGreaterThanOrEqualTo(radius - 0.1f);
    }

    /**
     * Incomplete buildings must NOT block movement — they are still under construction.
     */
    @Test
    void incompleteBuildingDoesNotBlockMovement() {
        // No terrain grid needed — building collision is always active, but incomplete
        // buildings must not block. Unit at (20,9) heading to (20,17): passes through
        // incomplete Hatchery at (20,13) and reaches past it.
        final var bldgPos = new Point2d(20f, 13f);
        game.enemy.buildings.add(new Building("wip-hatchery", BuildingType.HATCHERY,
            bldgPos, 1500, 1500, false)); // isComplete = false
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(20f, 9f));
        game.applyIntent(new MoveIntent(tag, new Point2d(20f, 17f)));
        for (int i = 0; i < 20; i++) game.tick();

        Unit unit = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        // Unit must have passed through — it must be beyond the building centre
        assertThat(unit.position().y())
            .as("Unit must pass through incomplete building — y should exceed building y=13")
            .isGreaterThan(13f);
    }

    @Test
    void enemyBehavior_trainsUnitsWhenMineralsAccumulate() {
        game.setEnemyStrategy(new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT), 10, new EnemyAttackConfig(3, 200, 0, 0)));
        game.reset();
        // Seed Gateway: EnemyBehavior now looks up real building tags.
        // setEnemyStrategy uses a permissive TechTree so no BuildIntent fires,
        // but trainedBy(ZEALOT)=GATEWAY means we must have one in enemy.buildings.
        game.spawnEnemyBuildingForTesting(BuildingType.GATEWAY, new Point2d(52, 51));
        game.enemy.minerals = 200;

        int zealotTrainTime = SC2Data.trainTimeInTicks(UnitType.ZEALOT);
        for (int i = 0; i < zealotTrainTime + 5; i++) game.tick();

        boolean hasZealot = game.enemy.stagingArea.stream().anyMatch(u -> u.type() == UnitType.ZEALOT)
            || game.enemy.units.stream().anyMatch(u -> u.type() == UnitType.ZEALOT);
        assertThat(hasZealot).isTrue();
    }

    // ---- #128: parallel training queues ----

    @Test
    void parallelTrainingTwoGateways() {
        game.setMineralsForTesting(500);
        game.setSupplyForTesting(200, 0);
        Building gw1 = game.spawnBuildingForTesting(BuildingType.GATEWAY, new Point2d(20, 20));
        Building gw2 = game.spawnBuildingForTesting(BuildingType.GATEWAY, new Point2d(22, 20));
        int before = game.snapshot().myUnits().size();

        game.applyIntent(new TrainIntent(gw1.tag(), UnitType.ZEALOT));
        game.applyIntent(new TrainIntent(gw2.tag(), UnitType.ZEALOT));

        for (int i = 0; i < 28; i++) game.tick(); // Zealot = 28 ticks
        assertThat(game.snapshot().myUnits()).hasSize(before + 2);
    }

    @Test
    void supplyReservedAtQueueTime() {
        game.setMineralsForTesting(500);
        // Initial: supply=15, supplyUsed=12 → 3 free; PROBE costs 1 supply
        assertThat(game.snapshot().supplyUsed()).isEqualTo(12);

        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE)); // starts training
        assertThat(game.snapshot().supplyUsed()).isEqualTo(13); // reserved immediately

        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE)); // queued behind first
        assertThat(game.snapshot().supplyUsed()).isEqualTo(14); // queued unit also reserves supply

        // No ticks yet — both increments are at queue time, not completion time
    }

    @Test
    void queueFullRejected() {
        game.setMineralsForTesting(5000);
        game.setSupplyForTesting(200, 0);

        // Fill queue to 5 total (1 training + 4 waiting)
        for (int i = 0; i < 5; i++) {
            game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));
        }
        assertThat(game.snapshot().supplyUsed()).isEqualTo(5); // 5 probes at 1 supply each

        // 6th must be rejected — supply unchanged
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));
        assertThat(game.snapshot().supplyUsed()).isEqualTo(5);
    }

    @Test
    void queueDrainsSequentially() {
        game.setMineralsForTesting(500);
        // supply=15, used=12, free=3 — enough for 3 probes at 1 each
        int before = game.snapshot().myUnits().size();

        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE)); // starts (12 ticks)
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE)); // queued

        for (int i = 0; i < 12; i++) game.tick(); // first completes
        assertThat(game.snapshot().myUnits()).hasSize(before + 1);

        for (int i = 0; i < 12; i++) game.tick(); // second completes (started right after first)
        assertThat(game.snapshot().myUnits()).hasSize(before + 2);
    }

    @Test
    void queuedUnitPreservesSubTickPrecision() {
        EmulatedGame game = new EmulatedGame();
        game.reset();
        game.setMineralsForTesting(500);
        game.tick(); // gameFrame = 1
        int unitsBefore = game.snapshot().myUnits().size();

        // Train Probe A at loop 10: offset=10, (10+272)/22 = 282/22 = 12
        // completesAt = 1 + 12 = 13
        game.applyIntent(new TimedIntent(10L, new TrainIntent("nexus-0", UnitType.PROBE)));
        // Queue Probe B behind Probe A
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));

        // Tick to frame 13: Probe A completes, drain starts Probe B
        for (int i = 0; i < 12; i++) game.tick();
        assertThat(game.snapshot().myUnits()).hasSize(unitsBefore + 1);

        // Tick 12 more: frames 14–25
        // Probe A's completion loop = 10 + 272 = 282; offset = 282 % 22 = 18
        // With propagation: (18 + 272) / 22 = 290 / 22 = 13; completesAt = 13 + 13 = 26
        // Without propagation (0L): (0 + 272) / 22 = 12; completesAt = 13 + 12 = 25
        for (int i = 0; i < 12; i++) game.tick();
        assertThat(game.snapshot().myUnits())
            .as("Queued unit should NOT complete yet — sub-tick offset pushes it 1 tick later")
            .hasSize(unitsBefore + 1);

        game.tick(); // frame 26
        assertThat(game.snapshot().myUnits())
            .as("Queued unit completes at frame 26 with propagated sub-tick offset")
            .hasSize(unitsBefore + 2);
    }

    @Test
    void completionLoopCleanedUpWithoutQueue() {
        game.setMineralsForTesting(500);
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE)); // single unit, no queue
        for (int i = 0; i < 13; i++) game.tick(); // completes
        // Verify no stale entry — train another unit later and verify it behaves normally
        // (if stale entry existed with a high offset, this second unit would complete later than expected)
        int unitsBefore = game.snapshot().myUnits().size();
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));
        for (int i = 0; i < 12; i++) game.tick();
        assertThat(game.snapshot().myUnits())
            .as("Second unit (no queue) should complete in normal 12 ticks, not shifted by stale offset")
            .hasSize(unitsBefore + 1);
    }

    @Test
    void buildingValidationRejectsUnknownTag() {
        game.setMineralsForTesting(500);
        int mineralsBefore = (int) game.snapshot().minerals();

        game.applyIntent(new TrainIntent("no-such-building", UnitType.ZEALOT));

        assertThat(game.snapshot().minerals()).isEqualTo(mineralsBefore); // no deduction
        assertThat(game.snapshot().supplyUsed()).isEqualTo(12);           // no supply change
    }

    @Test
    void wrongBuildingTypeRejected() {
        // Zealots require a Gateway, not a Nexus
        game.setMineralsForTesting(500);
        int mineralsBefore = (int) game.snapshot().minerals();

        game.applyIntent(new TrainIntent("nexus-0", UnitType.ZEALOT)); // Nexus cannot train Zealots

        assertThat(game.snapshot().minerals()).isEqualTo(mineralsBefore); // no deduction
        assertThat(game.snapshot().supplyUsed()).isEqualTo(12);           // no supply change
    }

    @Test
    void enemyUsesRealBuildingTagFromEnemyBuildings() {
        // setEnemyStrategy uses a permissive TechTree — we must also seed the
        // training building since EnemyBehavior now looks it up from enemy.buildings.
        game.setEnemyStrategy(new FixedBuildOrderStrategy("TEST", Race.PROTOSS,
            List.of(UnitType.ZEALOT), 10, new EnemyAttackConfig(3, 200, 0, 0)));
        game.reset();
        // Seed a Gateway so EnemyBehavior can find it via SC2Data.trainedBy(ZEALOT)=GATEWAY
        game.spawnEnemyBuildingForTesting(BuildingType.GATEWAY, new Point2d(52, 51));
        game.enemy.minerals = 200;

        int zealotTrainTime = SC2Data.trainTimeInTicks(UnitType.ZEALOT);
        for (int i = 0; i < zealotTrainTime + 5; i++) game.tick();

        boolean hasZealot = game.enemy.stagingArea.stream().anyMatch(u -> u.type() == UnitType.ZEALOT)
            || game.enemy.units.stream().anyMatch(u -> u.type() == UnitType.ZEALOT);
        assertThat(hasZealot).isTrue();
    }

    // ---- #129: auto-engage ----

    @Test
    void friendlyAutoEngagesWithoutAttackIntent() {
        // Zealot at (6.0, 9) — exactly 3.0 tiles from probe-0 at (9,9), within probe range.
        // probe-1 at (9.5,9) is 3.5 tiles away — out of range. Only probe-0 fires.
        // No AttackIntent issued — probe-0 must auto-engage.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(6.0f, 9.0f));
        int zealotMaxShields = SC2Data.maxShields(UnitType.ZEALOT); // 50
        int effective = Math.max(0, SC2Data.damagePerAttack(UnitType.PROBE) - SC2Data.armour(UnitType.ZEALOT)); // 5-1=4

        game.tick();

        Unit zealot = game.enemy.units.stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        // Exactly one probe auto-engaged — Zealot shields dropped by exactly one effective hit
        assertThat(zealot.shields()).isEqualTo(zealotMaxShields - effective);
    }

    // ---- E13: loop-aware training (TimedIntent) ----

    @Test
    void probeCompletesOnTimeWithZeroLoopOffset() {
        EmulatedGame game = new EmulatedGame();
        game.reset();
        game.tick(); // gameFrame = 1; matches harness pattern (applyIntent called post-tick)
        int unitsBefore = game.snapshot().myUnits().size();

        game.applyIntent(new TimedIntent(0L, new TrainIntent("nexus-0", UnitType.PROBE)));

        for (int i = 0; i < 11; i++) game.tick(); // ticks 2–12, gameFrame = 12
        assertThat(game.snapshot().myUnits().size())
            .as("Probe should not have completed yet after 11 ticks")
            .isEqualTo(unitsBefore);

        game.tick(); // tick 13, gameFrame = 13 — completesAt = 1 + 12 = 13, fires here
        assertThat(game.snapshot().myUnits().size())
            .as("Probe should complete after 12 ticks with zero loop offset")
            .isEqualTo(unitsBefore + 1);
    }

    @Test
    void probeCompletesOneLaterWithLateLoopOffset() {
        EmulatedGame game = new EmulatedGame();
        game.reset();
        game.tick(); // gameFrame = 1
        int unitsBefore = game.snapshot().myUnits().size();

        // loop=18: offset = 18 % 22 = 18; (18 + 272) / 22 = 290 / 22 = 13.18... → floor 13
        // completesAt = 1 + 13 = 14; fires at gameFrame=14, i.e., after 13 ticks
        game.applyIntent(new TimedIntent(18L, new TrainIntent("nexus-0", UnitType.PROBE)));

        for (int i = 0; i < 12; i++) game.tick(); // ticks 2–13, gameFrame = 13
        assertThat(game.snapshot().myUnits().size())
            .as("Probe should not complete after 12 ticks — late command pushes to tick 13")
            .isEqualTo(unitsBefore);

        game.tick(); // tick 14, gameFrame = 14 — completesAt = 14, fires here
        assertThat(game.snapshot().myUnits().size())
            .as("Probe should complete after 13 ticks with loop offset 18")
            .isEqualTo(unitsBefore + 1);
    }

    @Test
    void probeCompletesOneLaterWithBoundaryLoopOffset() {
        EmulatedGame game = new EmulatedGame();
        game.reset();
        game.tick(); // gameFrame = 1
        int unitsBefore = game.snapshot().myUnits().size();

        // loop=17: offset = 17 % 22 = 17; (17 + T_real) / 22 must give 13 ticks, not 12.
        // Float formula: (17 + 268.8) / 22 = 285.8 / 22 = 12.99... → floor 12 (wrong)
        // Integer formula: (17 + 272) / 22 = 289 / 22 = 13.13... → floor 13 (correct)
        // completesAt = 1 + 13 = 14; fires at gameFrame=14, i.e., after 13 ticks
        game.applyIntent(new TimedIntent(17L, new TrainIntent("nexus-0", UnitType.PROBE)));

        for (int i = 0; i < 12; i++) game.tick(); // ticks 2–13, gameFrame = 13
        assertThat(game.snapshot().myUnits().size())
            .as("Probe should not complete after 12 ticks — offset 17 is the boundary that requires 13 ticks")
            .isEqualTo(unitsBefore);

        game.tick(); // tick 14, gameFrame = 14 — completesAt = 14, fires here
        assertThat(game.snapshot().myUnits().size())
            .as("Probe should complete after 13 ticks with boundary loop offset 17")
            .isEqualTo(unitsBefore + 1);
    }

    // --- injectReplayBuildingWithCost ---

    @Test
    void injectReplayBuildingWithCost_deductsMineralCost() {
        game.setMineralsForTesting(500);
        Building gateway = new Building("r-1-1", BuildingType.GATEWAY,
            new Point2d(20, 20),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            false);

        game.injectReplayBuildingWithCost(gateway);

        assertThat(game.snapshot().minerals())
            .as("Gateway costs 150 minerals — should be deducted")
            .isEqualTo(350); // 500 - 150
    }

    @Test
    void injectReplayBuildingWithCost_addsBuildingToState() {
        game.setMineralsForTesting(500);
        Building gateway = new Building("r-1-1", BuildingType.GATEWAY,
            new Point2d(20, 20),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            false);
        int buildingsBefore = game.snapshot().myBuildings().size();

        game.injectReplayBuildingWithCost(gateway);

        assertThat(game.snapshot().myBuildings()).hasSize(buildingsBefore + 1);
        assertThat(game.snapshot().myBuildings())
            .anyMatch(b -> b.tag().equals("r-1-1") && b.type() == BuildingType.GATEWAY);
    }

    @Test
    void injectReplayBuildingWithCost_allowsMineralDebtWhenInsufficientBalance() {
        game.setMineralsForTesting(50); // less than Gateway cost (150)
        Building gateway = new Building("r-1-1", BuildingType.GATEWAY,
            new Point2d(20, 20),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            SC2Data.maxBuildingHealth(BuildingType.GATEWAY),
            false);

        game.injectReplayBuildingWithCost(gateway);

        // Minerals go negative (debt), matching the real player's constrained state.
        // The debt is repaid through mining income over subsequent ticks.
        assertThat(game.snapshot().minerals())
            .as("Mineral debt is tracked — balance is 50 - 150 = -100")
            .isEqualTo(-100);
        assertThat(game.snapshot().myBuildings())
            .as("Building is still injected even with mineral debt")
            .anyMatch(b -> b.tag().equals("r-1-1"));
    }

    @Test
    void injectReplayBuilding_doesNotDeductMinerals() {
        game.setMineralsForTesting(500);
        Building nexus = new Building("r-0-1", BuildingType.NEXUS,
            new Point2d(8, 8),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            true);

        game.injectReplayBuilding(nexus);

        assertThat(game.snapshot().minerals())
            .as("Free injection must not deduct minerals")
            .isEqualTo(500);
    }
}
