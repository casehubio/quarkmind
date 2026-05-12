package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Drives the enemy player each game tick by accumulating minerals, queuing
 * unit training, launching attacks, and managing retreats.
 *
 * <p>Tech-tree gating: if a target unit's prerequisites are not met, a BuildIntent
 * for the first missing prerequisite building is queued instead. Buildings are
 * deduplicated via {@code pendingBuildings} — a prereq that is already pending or
 * already built is never re-queued.
 *
 * <p>Package-private: wired by {@link EmulatedGame} which is in the same package.
 */
class EnemyBehavior implements PlayerBehavior {

    private static final Logger log = Logger.getLogger(EnemyBehavior.class);

    static final Point2d NEXUS_POS   = new Point2d(8, 8);
    static final Point2d STAGING_POS = new Point2d(26, 26);

    private EnemyStrategy strategy;
    private final PlayerState enemy;
    private final TechTree techTree;

    // Production state
    private Optional<UnitType> currentTarget = Optional.empty();
    private boolean trainingPending = false;  // waiting for a previously issued TrainIntent to complete
    private int nextTag = 1000;
    // Tracks building types currently pending completion — prevents duplicate BuildIntents
    private final Set<BuildingType> pendingBuildings = new HashSet<>();

    // Attack state — Long.MAX_VALUE on init means "first attack, no cooldown applies"
    private long framesSinceLastAttack = Long.MAX_VALUE;
    private int initialAttackSize = 0;

    // Retreat state — package-private for EmulatedGame.moveEnemyUnits()
    private final Set<String> retreating = new HashSet<>();

    EnemyBehavior(EnemyStrategy strategy, PlayerState enemy, TechTree techTree) {
        this.strategy  = strategy;
        this.enemy     = enemy;
        this.techTree  = techTree;
    }

    @Override
    public void tick(GameState observation, IntentQueue queue) {
        accumulateMinerals();
        tickStrategySwitch(observation);
        tickProduction(observation, queue);
        tickAttackLaunch(observation, queue);
        tickRetreat(observation, queue);
        framesSinceLastAttack++;
    }

    // -------------------------------------------------------------------------
    // Strategy switching
    // -------------------------------------------------------------------------

    private void tickStrategySwitch(GameState observation) {
        EnemyObservation obs = buildObservation(observation);
        if (!strategy.shouldSwitch(obs)) return;

        if (strategy instanceof ReactiveStrategy r) {
            // ReactiveStrategy resolves its counter internally and stays active as the
            // outer wrapper — the same instance continues driving future re-evaluations.
            r.resolveCounter();
        } else {
            // Fixed strategies are replaced wholesale with a random strategy of the same race.
            strategy = EnemyStrategyLibrary.randomForRace(strategy.race());
        }
        currentTarget = Optional.empty();
        log.infof("[ENEMY] Strategy re-evaluated: %s", strategy.name());
    }

    // -------------------------------------------------------------------------
    // Mineral accumulation
    // -------------------------------------------------------------------------

    private void accumulateMinerals() {
        enemy.minerals += strategy.mineralsPerTick();
    }

    // -------------------------------------------------------------------------
    // Production
    // -------------------------------------------------------------------------

    private void tickProduction(GameState observation, IntentQueue queue) {
        // Prune pendingBuildings: any type now present in enemy.buildings is no longer pending
        pendingBuildings.removeIf(bt -> builtBuildingTypes().contains(bt));

        // If a TrainIntent was previously issued but not yet reflected in enemy.units,
        // wait until the unit appears (i.e., enemy.units grows). For simplicity in the
        // emulated game, we track a boolean flag: trainingPending. EmulatedGame calls
        // applyEnemyTrain() which calls back notifyUnitTrained() to clear the flag.
        // But since EnemyBehavior doesn't yet know when a unit finishes training
        // (EmulatedGame handles the completion timer), we use a simpler heuristic:
        // once a TrainIntent is queued, we do NOT queue another until told otherwise.
        // The flag is cleared by notifyUnitTrained() called from EmulatedGame.
        if (trainingPending) return;

        // Pick a target if we don't have one
        if (currentTarget.isEmpty()) {
            EnemyObservation obs = buildObservation(observation);
            currentTarget = strategy.nextUnit(obs);
        }

        if (currentTarget.isEmpty()) return;

        UnitType target = currentTarget.get();

        // Tech-tree gate: if prerequisites are not met, queue the first missing building
        Set<BuildingType> built = builtBuildingTypes();
        if (!techTree.canTrain(target, built)) {
            techTree.nextRequired(target, built).ifPresent(prereq -> {
                if (!pendingBuildings.contains(prereq)) {
                    int bCost = SC2Data.mineralCost(prereq);
                    if (enemy.minerals >= bCost) {
                        enemy.minerals -= bCost;
                        String tag = "ebldg-" + nextTag++;
                        Point2d pos = buildingPosition(pendingBuildings.size());
                        queue.add(new BuildIntent(tag, prereq, pos));
                        pendingBuildings.add(prereq);
                        log.debugf("[ENEMY] Queued BuildIntent: %s (prereq for %s)", prereq, target);
                    }
                }
            });
            return;
        }

        int cost = SC2Data.mineralCost(target);
        if (enemy.minerals < cost) return;

        // Afford it — issue the intent; handleTrain() does the actual mineral deduction.
        // Do NOT deduct here: double deduction was the bug (EnemyBehavior deducted, then handleTrain deducted again).
        BuildingType needed = SC2Data.trainedBy(target);
        Optional<Building> trainer = enemy.buildings.stream()
            .filter(b -> b.isComplete() && b.type() == needed)
            .findFirst();
        if (trainer.isEmpty()) {
            log.debugf("[ENEMY] No ready %s to train %s — waiting", needed, target);
            return;
        }
        queue.add(new TrainIntent(trainer.get().tag(), target));
        trainingPending = true;
        currentTarget = Optional.empty();
        log.debugf("[ENEMY] Queued TrainIntent: %s from %s (cost=%d, minerals_left=%.0f)",
            target, trainer.get().tag(), cost, enemy.minerals);
    }

    private Set<BuildingType> builtBuildingTypes() {
        Set<BuildingType> built = new HashSet<>();
        for (Building b : enemy.buildings) built.add(b.type());
        return built;
    }

    private static Point2d buildingPosition(int index) {
        // Place buildings in a cluster at tile (50+index, 50) — far corner
        return new Point2d(50 + index, 50);
    }

    /**
     * Called by EmulatedGame when a pending enemy TrainIntent's unit completes.
     * Clears the trainingPending flag so the next unit can be queued.
     */
    void notifyUnitTrained() {
        trainingPending = false;
    }

    // -------------------------------------------------------------------------
    // Attack launch
    // -------------------------------------------------------------------------

    private void tickAttackLaunch(GameState observation, IntentQueue queue) {
        EnemyAttackConfig atk = strategy.attackConfig();
        int stagingSize = enemy.stagingArea.size();
        if (stagingSize == 0) return;
        boolean thresholdMet = stagingSize >= atk.armyThreshold();
        boolean timerFired   = framesSinceLastAttack >= atk.attackIntervalFrames();
        if (!thresholdMet && !timerFired) return;

        initialAttackSize = stagingSize;
        for (Unit u : enemy.stagingArea) {
            enemy.units.add(u);
            queue.add(new AttackIntent(u.tag(), NEXUS_POS));
        }
        log.infof("[ENEMY] Attack launched: %d units (threshold=%b timer=%b)",
            stagingSize, thresholdMet, timerFired);
        enemy.stagingArea.clear();
        framesSinceLastAttack = 0;
    }

    // -------------------------------------------------------------------------
    // Retreat
    // -------------------------------------------------------------------------

    private void tickRetreat(GameState observation, IntentQueue queue) {
        if (initialAttackSize == 0) return;

        EnemyAttackConfig atk = strategy.attackConfig();

        // 1. Per-unit health threshold
        if (atk.retreatHealthPercent() > 0) {
            for (Unit u : enemy.units) {
                if (retreating.contains(u.tag())) continue;
                double totalHp    = u.health() + u.shields();
                double maxTotalHp = (double) u.maxHealth() + u.maxShields();
                if (maxTotalHp > 0 && totalHp / maxTotalHp * 100 < atk.retreatHealthPercent()) {
                    retreating.add(u.tag());
                    queue.add(new MoveIntent(u.tag(), STAGING_POS));
                    log.debugf("[ENEMY] Unit %s retreating (%.1f%% hp)", u.tag(),
                        totalHp / maxTotalHp * 100);
                }
            }
        }

        // 2. Army-wide depletion threshold
        if (atk.retreatArmyPercent() > 0) {
            double survivingPct = (double) enemy.units.size() / initialAttackSize * 100;
            if (survivingPct < atk.retreatArmyPercent()) {
                for (Unit u : enemy.units) {
                    if (retreating.contains(u.tag())) continue;
                    retreating.add(u.tag());
                    queue.add(new MoveIntent(u.tag(), STAGING_POS));
                }
                log.infof("[ENEMY] Army retreat: %.0f%% surviving (%d/%d)",
                    survivingPct, enemy.units.size(), initialAttackSize);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the current strategy. Package-private for EmulatedGame reset(). */
    EnemyStrategy currentStrategy() {
        return strategy;
    }

    /** Returns the current set of retreating unit tags. Package-private for EmulatedGame. */
    Set<String> retreatingUnits() {
        return Collections.unmodifiableSet(retreating);
    }

    /** Marks a unit as no longer retreating (arrived at staging). Package-private. */
    void clearRetreating(String tag) {
        retreating.remove(tag);
    }

    /** Test helper — simulates a wave having been launched without actually moving units. */
    void setInitialAttackSizeForTesting(int n) {
        this.initialAttackSize = n;
    }

    /** Test helper — sets the frames-since-last-attack counter directly. */
    void setFramesSinceLastAttackForTesting(long n) {
        this.framesSinceLastAttack = n;
    }

    /** Resets all state for a new game or strategy switch. */
    void reset(EnemyStrategy newStrategy) {
        this.strategy         = newStrategy;
        this.currentTarget    = Optional.empty();
        this.trainingPending  = false;
        this.framesSinceLastAttack = Long.MAX_VALUE;
        this.initialAttackSize = 0;
        this.retreating.clear();
        this.pendingBuildings.clear();
        this.nextTag = 1000;
        newStrategy.reset();

        // Seed enemy main structure (free — no mineral cost applied here; minerals seeded separately)
        BuildingType main = switch (newStrategy.race()) {
            case PROTOSS -> BuildingType.NEXUS;
            case TERRAN  -> BuildingType.COMMAND_CENTER;
            case ZERG    -> BuildingType.HATCHERY;
        };
        int hp = SC2Data.maxBuildingHealth(main);
        enemy.buildings.add(new Building("enemy-main", main,
            new Point2d(50, 51), hp, hp, true));
        log.debugf("[ENEMY] Seeded main structure: %s", main);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private EnemyObservation buildObservation(GameState obs) {
        return new EnemyObservation(
            obs.myUnits(),                 // from enemy's POV, "player units" = the friendly units
            Set.of(),                      // enemy buildings not tracked in emulated game
            (int) enemy.minerals,
            obs.gameFrame()
        );
    }
}
