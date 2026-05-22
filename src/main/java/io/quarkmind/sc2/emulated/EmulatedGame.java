package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.*;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Phase E2 physics engine.
 * Probe-driven mineral harvesting, unit movement, scripted enemy waves,
 * and full intent handling (train/build/move/attack).
 * Not a CDI bean — owned and instantiated by {@link EmulatedEngine}.
 */
public class EmulatedGame {

    private static final Logger log = Logger.getLogger(EmulatedGame.class);

    // --- Per-player state ---
    final PlayerState friendly = new PlayerState();
    final PlayerState enemy    = new PlayerState();

    // --- Enemy AI ---
    private EnemyBehavior enemyBehavior;
    private final IntentQueue enemyIntentQueue = new IntentQueue();

    // --- Shared / game-level state ---
    private long gameFrame;
    private int[] miningProbesPerBase;
    private int  nextTag = 200;
    private double unitSpeed = 0.5;
    private final List<Resource> geysers = new ArrayList<>();
    private final List<EnemyWave> pendingWaves = new ArrayList<>();
    private final DamageCalculator damageCalculator = new DamageCalculator();
    private MovementStrategy movementStrategy = new DirectMovement();
    // E7: hard physics constraint — no unit may land on a wall tile regardless of movement strategy.
    // Null in mock/test contexts where no terrain exists.
    private TerrainGrid terrainGrid = null;
    private Random random = new Random(42L);
    // E9: fog of war — persists across ticks, reset on game restart
    private final VisibilityGrid visibility = new VisibilityGrid();

    public void reset() {
        friendly.clear();
        enemy.clear();
        nextTag     = 200;
        gameFrame   = 0;
        miningProbesPerBase = new int[]{SC2Data.INITIAL_PROBES};
        movementStrategy.reset();
        visibility.reset();
        geysers.clear();
        // pendingWaves intentionally NOT cleared — configured before reset() via configureWave()
        // terrainGrid and random intentionally NOT cleared — set by EmulatedEngine before reset(), persist across resets

        // Seed friendly player
        friendly.minerals   = SC2Data.INITIAL_MINERALS;
        friendly.vespene    = SC2Data.INITIAL_VESPENE;
        friendly.supply     = SC2Data.INITIAL_SUPPLY;
        friendly.supplyUsed = SC2Data.INITIAL_SUPPLY_USED;
        for (int i = 0; i < SC2Data.INITIAL_PROBES; i++) {
            friendly.units.add(new Unit("probe-" + i, UnitType.PROBE,
                new Point2d(9 + i * 0.5f, 9),
                SC2Data.maxHealth(UnitType.PROBE), SC2Data.maxHealth(UnitType.PROBE),
                SC2Data.maxShields(UnitType.PROBE), SC2Data.maxShields(UnitType.PROBE), 0, 0));
        }
        friendly.buildings.add(new Building("nexus-0", BuildingType.NEXUS,
            new Point2d(8, 8),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            true));
        geysers.add(new Resource("geyser-0", new Point2d(5, 11), 2250));
        geysers.add(new Resource("geyser-1", new Point2d(11, 5), 2250));

        // Seed enemy player with effectively unlimited supply and gas so TrainIntents are never
        // rejected by resource checks. Minerals are earned each tick via EnemyBehavior.accumulateMinerals().
        enemy.supply  = 200;
        enemy.vespene = 9999;

        // Reset enemy behavior (if set)
        if (enemyBehavior != null) enemyBehavior.reset(enemyBehavior.currentStrategy());
    }

    public void tick() {
        gameFrame++;
        for (int probesAtBase : miningProbesPerBase) {
            friendly.minerals += SC2Data.mineralIncomePerTick(probesAtBase);
        }
        moveFriendlyUnits();
        // Recompute after movement, before combat: a unit that dies this tick still
        // provided vision for this frame — correct SC2 behaviour.
        visibility.recompute(friendly.units, friendly.buildings, terrainGrid);
        moveEnemyUnits();
        resolveCombat();
        tickEnemyRetreatTransfer();
        friendly.fireCompletions(gameFrame);
        enemy.fireCompletions(gameFrame);
        drainBuildingQueues(friendly);
        drainBuildingQueues(enemy);
        spawnEnemyWaves();
        // Enemy behavior tick
        if (enemyBehavior != null) {
            GameState enemyPov = snapshotForEnemy();
            enemyBehavior.tick(enemyPov, enemyIntentQueue);
            enemyIntentQueue.drainAll().forEach(intent -> applyIntent(intent, enemy));
        }
    }

    /**
     * Hard physics wall constraint — applied after every movement strategy call.
     * If the proposed position lands on a wall tile, the unit stays put.
     * This is independent of pathfinding and acts as an inviolable backstop.
     */
    private Point2d enforceWall(String unitTag, Point2d proposed, Point2d current) {
        // Terrain wall — requires a terrain grid
        if (terrainGrid != null) {
            int tx = (int) proposed.x();
            int ty = (int) proposed.y();
            if (!terrainGrid.isWalkable(tx, ty)) {
                log.warnf("[PHYSICS] Wall collision blocked %s at (%.2f,%.2f) tile(%d,%d) — invalidating path",
                    unitTag, proposed.x(), proposed.y(), tx, ty);
                movementStrategy.invalidatePath(unitTag);
                return current;
            }
        }
        // Building collision — always active; both sides' completed buildings are solid.
        // Only block entry: units already inside a radius (e.g. workers near Nexus) move freely.
        for (var bldg : friendly.buildings) {
            float r = SC2Data.buildingRadius(bldg.type());
            if (bldg.isComplete()
                    && distance(current, bldg.position()) >= r
                    && distance(proposed, bldg.position()) < r) {
                movementStrategy.invalidatePath(unitTag);
                return current;
            }
        }
        for (var bldg : enemy.buildings) {
            float r = SC2Data.buildingRadius(bldg.type());
            if (bldg.isComplete()
                    && distance(current, bldg.position()) >= r
                    && distance(proposed, bldg.position()) < r) {
                movementStrategy.invalidatePath(unitTag);
                return current;
            }
        }
        return proposed;
    }

    private void moveFriendlyUnits() {
        friendly.units.replaceAll(u -> {
            Point2d target = friendly.unitTargets.get(u.tag());
            if (target == null) return u;
            Point2d newPos = enforceWall(u.tag(),
                movementStrategy.advance(u.tag(), u.position(), target, unitSpeed),
                u.position());
            if (distance(newPos, target) < 0.2) friendly.unitTargets.remove(u.tag());
            return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth(),
                            u.shields(), u.maxShields(), 0, 0);
        });
    }

    private void moveEnemyUnits() {
        Set<String> retreating = enemyBehavior != null ? enemyBehavior.retreatingUnits() : Set.of();
        enemy.units.replaceAll(u -> {
            Point2d target = enemy.unitTargets.getOrDefault(u.tag(), EnemyBehavior.NEXUS_POS);
            Point2d newPos = enforceWall(u.tag(),
                movementStrategy.advance(u.tag(), u.position(), target, unitSpeed),
                u.position());
            // Log when a unit crosses the wall row (y transitions through 18)
            float prevY = u.position().y();
            float nextY = newPos.y();
            if ((prevY > 18f && nextY <= 18f) || (prevY >= 18f && nextY < 18f)) {
                log.infof("[WALL-CROSS] %s crossed y=18: from (%.2f,%.2f) to (%.2f,%.2f) — x=%.2f %s",
                    u.tag(), u.position().x(), prevY, newPos.x(), nextY, newPos.x(),
                    (newPos.x() >= 11f && newPos.x() <= 14f) ? "IN GAP ✓" : "THROUGH WALL ✗");
            }
            return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth(),
                            u.shields(), u.maxShields(), 0, 0);
        });
    }

    /**
     * Transfers retreating enemy units that have arrived at staging back to enemy.stagingArea.
     * The retreat decision (marking units, issuing MoveIntent) is done by EnemyBehavior.
     * EmulatedGame detects arrival and transfers.
     */
    private void tickEnemyRetreatTransfer() {
        if (enemyBehavior == null) return;
        Set<String> retreating = enemyBehavior.retreatingUnits();
        enemy.units.removeIf(u -> {
            if (!retreating.contains(u.tag())) return false;
            if (distance(u.position(), EnemyBehavior.STAGING_POS) >= 0.1) return false;
            enemyBehavior.clearRetreating(u.tag());
            enemy.unitTargets.remove(u.tag());
            movementStrategy.clearUnit(u.tag());
            enemy.stagingArea.add(u);
            log.debugf("[EMULATED] Unit %s arrived at staging (hp=%d shields=%d)",
                u.tag(), u.health(), u.shields());
            return true;
        });
    }

    private void spawnEnemyWaves() {
        pendingWaves.removeIf(wave -> {
            if (wave.spawnFrame() > gameFrame) return false;
            for (int i = 0; i < wave.unitTypes().size(); i++) {
                UnitType type = wave.unitTypes().get(i);
                Point2d pos = new Point2d(wave.spawnPosition().x() + i * 0.5f,
                                          wave.spawnPosition().y());
                String tag = "enemy-" + nextTag++;
                int hp = SC2Data.maxHealth(type);
                enemy.units.add(new Unit(tag, type, pos, hp, hp,
                    SC2Data.maxShields(type), SC2Data.maxShields(type), 0, 0));
                enemy.unitTargets.put(tag, wave.targetPosition());
            }
            log.infof("[EMULATED] Enemy wave spawned: %dx%s at frame %d",
                wave.unitTypes().size(), wave.unitTypes().get(0), gameFrame);
            return true;
        });
    }


    public void applyIntent(Intent intent) {
        applyIntent(intent, friendly);
    }

    public void applyIntent(TimedIntent ti) {
        Runnable action = switch (ti.intent()) {
            case TrainIntent  t -> () -> handleTrain(t, friendly, ti.loop());
            case MoveIntent   m -> () -> setTarget(m.unitTag(), m.targetLocation(), false, friendly);
            case AttackIntent a -> () -> setTarget(a.unitTag(), a.targetLocation(), true,  friendly);
            case BuildIntent  b -> () -> handleBuild(b, friendly);
            case BlinkIntent  b -> () -> executeBlink(b.unitTag(), friendly);
        };
        action.run();
    }

    void applyIntent(Intent intent, PlayerState state) {
        Runnable action = switch (intent) {
            case MoveIntent   m -> () -> setTarget(m.unitTag(), m.targetLocation(), false, state);
            case AttackIntent a -> () -> setTarget(a.unitTag(), a.targetLocation(), true,  state);
            case TrainIntent  t -> () -> handleTrain(t, state);
            case BuildIntent  b -> () -> handleBuild(b, state);
            case BlinkIntent  b -> () -> executeBlink(b.unitTag(), state);
        };
        action.run();
    }

    private void setTarget(String tag, Point2d target, boolean isAttack, PlayerState state) {
        if (state.units.stream().anyMatch(u -> u.tag().equals(tag))) {
            state.unitTargets.put(tag, target);
            if (isAttack) state.attackingUnits.add(tag);
            else          state.attackingUnits.remove(tag);  // dead write — cleanup tracked in #134
            log.debugf("[EMULATED] %s → (%.1f,%.1f) attack=%b", tag, target.x(), target.y(), isAttack);
        }
    }

    private void handleTrain(TrainIntent t, PlayerState state) {
        handleTrain(t, state, 0L);
    }

    private void handleTrain(TrainIntent t, PlayerState state, long absLoop) {
        String buildingTag = t.buildingTag();
        Building building = state.buildings.stream()
            .filter(b -> b.tag().equals(buildingTag) && b.isComplete())
            .findFirst().orElse(null);
        if (building == null) {
            log.debugf("[EMULATED] Train rejected — building %s not ready", buildingTag);
            return;
        }
        BuildingType required = SC2Data.trainedBy(t.unitType());
        if (required != BuildingType.UNKNOWN && building.type() != required) {
            log.debugf("[EMULATED] Train rejected — %s cannot train %s (needs %s)",
                building.type(), t.unitType(), required);
            return;
        }
        int mCost = SC2Data.mineralCost(t.unitType());
        int gCost = SC2Data.gasCost(t.unitType());
        int sCost = SC2Data.supplyCost(t.unitType());
        if ((int) state.minerals < mCost || state.vespene < gCost
                || state.supplyUsed + sCost > state.supply) {
            log.debugf("[EMULATED] Cannot train %s — insufficient resources", t.unitType());
            return;
        }
        boolean isBusy = state.buildingTrainingUntil.containsKey(buildingTag);
        Deque<UnitType> existingQueue = state.buildingQueues.get(buildingTag);
        int total = (isBusy ? 1 : 0) + (existingQueue != null ? existingQueue.size() : 0);
        if (total >= 5) {
            log.debugf("[EMULATED] Train rejected — building %s queue full", buildingTag);
            return;
        }
        state.supplyUsed += sCost;
        state.minerals   -= mCost;
        state.vespene    -= gCost;
        if (!isBusy) {
            startTraining(buildingTag, t.unitType(), state, absLoop);
        } else {
            state.buildingQueues.computeIfAbsent(buildingTag, k -> new ArrayDeque<>())
                .add(t.unitType());
        }
    }

    private void startTraining(String buildingTag, UnitType unitType, PlayerState state, long absLoop) {
        boolean isEnemy  = (state == enemy);
        int  loopOffset  = (int)(absLoop % SC2Data.LOOPS_PER_TICK);
        long completesAt = gameFrame
            + (loopOffset + SC2Data.trainTimeInLoops(unitType)) / SC2Data.LOOPS_PER_TICK;
        state.buildingTrainingUntil.put(buildingTag, completesAt);
        state.buildingCompletionAtLoop.put(buildingTag,
            absLoop + SC2Data.trainTimeInLoops(unitType));
        state.pendingCompletions.add(new PlayerState.PendingCompletion(completesAt, () -> {
            state.buildingTrainingUntil.remove(buildingTag);
            if (!state.buildingQueues.containsKey(buildingTag)) {
                state.buildingCompletionAtLoop.remove(buildingTag);
            }
            String tag = "unit-" + nextTag++;
            int hp = SC2Data.maxHealth(unitType);
            state.units.add(new Unit(tag, unitType, new Point2d(9, 9), hp, hp,
                SC2Data.maxShields(unitType), SC2Data.maxShields(unitType), 0, 0));
            log.debugf("[EMULATED] Trained %s (tag=%s)", unitType, tag);
            if (isEnemy && enemyBehavior != null) {
                enemyBehavior.notifyUnitTrained();
            }
        }));
    }

    private void drainBuildingQueues(PlayerState state) {
        for (String buildingTag : new ArrayList<>(state.buildingQueues.keySet())) {
            if (state.buildingTrainingUntil.containsKey(buildingTag)) continue;
            Deque<UnitType> queue = state.buildingQueues.get(buildingTag);
            if (queue == null || queue.isEmpty()) {
                state.buildingQueues.remove(buildingTag);
                state.buildingCompletionAtLoop.remove(buildingTag);
                continue;
            }
            UnitType next = queue.poll();
            if (queue.isEmpty()) state.buildingQueues.remove(buildingTag);
            long completionLoop = state.buildingCompletionAtLoop.getOrDefault(buildingTag, 0L);
            state.buildingCompletionAtLoop.remove(buildingTag);
            startTraining(buildingTag, next, state, completionLoop);
        }
    }

    private void handleBuild(BuildIntent b, PlayerState state) {
        int mCost = SC2Data.mineralCost(b.buildingType());
        if ((int) state.minerals < mCost) {
            log.debugf("[EMULATED] Cannot build %s — insufficient minerals", b.buildingType());
            return;
        }
        state.minerals -= mCost;
        String tag = "bldg-" + nextTag++;
        BuildingType bt = b.buildingType();
        state.buildings.add(new Building(tag, bt, b.location(),
            SC2Data.maxBuildingHealth(bt), SC2Data.maxBuildingHealth(bt), false));
        long completesAt = gameFrame + SC2Data.buildTimeInTicks(bt);
        state.pendingCompletions.add(new PlayerState.PendingCompletion(completesAt, () -> {
            markBuildingComplete(tag, state);
            state.supply += SC2Data.supplyBonus(bt);
            log.debugf("[EMULATED] Completed %s (tag=%s)", bt, tag);
        }));
    }

    private void markBuildingComplete(String tag, PlayerState state) {
        state.buildings.replaceAll(b -> b.tag().equals(tag)
            ? new Building(b.tag(), b.type(), b.position(), b.health(), b.maxHealth(), true)
            : b);
    }

    private void resolveCombat() {
        // Step 1: decrement all cooldowns (floor 0)
        friendly.unitCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));
        enemy.unitCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));
        friendly.blinkCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));
        enemy.blinkCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));

        Map<String, Integer> pending       = new HashMap<>();
        Set<String>          firedFriendly = new HashSet<>();
        Set<String>          firedEnemy    = new HashSet<>();

        // Step 2: collect damage from units where cooldown == 0
        for (Unit attacker : friendly.units) {
            if (friendly.unitCooldowns.getOrDefault(attacker.tag(), 0) > 0) continue;
            nearestInRange(attacker.position(), enemy.units, SC2Data.attackRange(attacker.type()))
                .ifPresent(target -> {
                    if (!missesHighGround(attacker.position(), target.position(), attacker.type())) {
                        pending.merge(target.tag(),
                            damageCalculator.computeEffective(attacker.type(), target), Integer::sum);
                    }
                    firedFriendly.add(attacker.tag()); // cooldown resets even on miss
                });
        }
        for (Unit attacker : enemy.units) {
            if (enemy.unitCooldowns.getOrDefault(attacker.tag(), 0) > 0) continue;
            nearestInRange(attacker.position(), friendly.units, SC2Data.attackRange(attacker.type()))
                .ifPresent(target -> {
                    if (!missesHighGround(attacker.position(), target.position(), attacker.type())) {
                        pending.merge(target.tag(),
                            damageCalculator.computeEffective(attacker.type(), target), Integer::sum);
                    }
                    firedEnemy.add(attacker.tag()); // cooldown resets even on miss
                });
        }

        // Step 3: apply damage — two-pass (collect all, then apply)
        friendly.units.replaceAll(u -> applyDamage(u, pending.getOrDefault(u.tag(), 0)));
        friendly.units.removeIf(u -> {
            if (u.health() <= 0) {
                friendly.unitTargets.remove(u.tag());
                friendly.attackingUnits.remove(u.tag());
                friendly.unitCooldowns.remove(u.tag());
                friendly.blinkCooldowns.remove(u.tag());
                movementStrategy.clearUnit(u.tag());
                return true;
            }
            return false;
        });
        enemy.units.replaceAll(u -> applyDamage(u, pending.getOrDefault(u.tag(), 0)));
        enemy.units.removeIf(u -> {
            if (u.health() <= 0) {
                enemy.unitTargets.remove(u.tag());
                enemy.unitCooldowns.remove(u.tag());
                if (enemyBehavior != null) enemyBehavior.clearRetreating(u.tag());
                movementStrategy.clearUnit(u.tag());
                return true;
            }
            return false;
        });

        // Step 4: reset cooldown for units that fired
        for (Unit u : friendly.units) {
            if (firedFriendly.contains(u.tag()))
                friendly.unitCooldowns.put(u.tag(), SC2Data.attackCooldownInTicks(u.type()));
        }
        for (Unit u : enemy.units) {
            if (firedEnemy.contains(u.tag()))
                enemy.unitCooldowns.put(u.tag(), SC2Data.attackCooldownInTicks(u.type()));
        }
    }

    private static Optional<Unit> nearestInRange(Point2d from, List<Unit> candidates, float range) {
        return candidates.stream()
            .filter(u -> distance(from, u.position()) <= range)
            .min(Comparator.comparingDouble(u ->
                distance(from, u.position()) * 1000 + u.health() + u.shields()));
    }

    /**
     * Computes blink retreat target for a unit. Uses the opposing player's units as threat source.
     */
    private Point2d blinkRetreatTarget(Unit unit, PlayerState opponents) {
        Unit nearest = opponents.units.stream()
            .min(Comparator.comparingDouble(e -> distance(unit.position(), e.position())))
            .orElse(null);
        if (nearest == null) return unit.position();
        double dx = unit.position().x() - nearest.position().x();
        double dy = unit.position().y() - nearest.position().y();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) return unit.position();
        float step = SC2Data.blinkRange(unit.type());
        double baseAngle = Math.atan2(dy, dx);
        // Try direct direction first, then sweep ±45° increments if wall
        for (int i = 0; i <= 4; i++) {
            for (int sign : new int[]{1, -1}) {
                double angle = baseAngle + sign * i * Math.PI / 4;
                Point2d candidate = new Point2d(
                    (float)(unit.position().x() + Math.cos(angle) * step),
                    (float)(unit.position().y() + Math.sin(angle) * step));
                if (terrainGrid == null || terrainGrid.isWalkable((int) candidate.x(), (int) candidate.y())) return candidate;
                if (i == 0) break; // only one attempt for direct direction
            }
        }
        return unit.position(); // all directions blocked — stay put
    }

    private void executeBlink(String tag, PlayerState state) {
        PlayerState opponents = (state == friendly) ? enemy : friendly;
        state.units.replaceAll(u -> {
            if (!u.tag().equals(tag)) return u;
            Point2d dest = blinkRetreatTarget(u, opponents);
            int restored = Math.min(u.shields() + SC2Data.blinkShieldRestore(u.type()), u.maxShields());
            state.unitTargets.put(tag, dest);
            state.blinkCooldowns.put(tag, SC2Data.blinkCooldownInTicks(u.type()));
            state.attackingUnits.remove(tag); // blink cancels attack mode
            return new Unit(u.tag(), u.type(), dest,
                            u.health(), u.maxHealth(), restored, u.maxShields(), 0, 0);
        });
    }

    /**
     * Returns true if this attack should miss due to low-ground-to-high-ground penalty.
     * Condition: attacker on LOW, target on HIGH, attack is ranged (range > 1.0), and RNG says miss.
     * Returns false (no miss) when terrainGrid is null or the height condition is not met.
     */
    private boolean missesHighGround(Point2d attackerPos, Point2d targetPos, UnitType attackerType) {
        if (terrainGrid == null) return false;
        if (SC2Data.attackRange(attackerType) <= 1.0f) return false; // melee — never penalised
        TerrainGrid.Height ah = terrainGrid.heightAt((int) attackerPos.x(), (int) attackerPos.y());
        TerrainGrid.Height th = terrainGrid.heightAt((int) targetPos.x(),   (int) targetPos.y());
        if (ah != TerrainGrid.Height.LOW || th != TerrainGrid.Height.HIGH) return false;
        return random.nextDouble() < 0.25;
    }

    private static Unit applyDamage(Unit u, int damage) {
        if (damage <= 0) return u;
        int shieldsLeft = Math.max(0, u.shields() - damage);
        int overflow    = Math.max(0, damage - u.shields());
        int hpLeft      = Math.max(0, u.health() - overflow);
        return new Unit(u.tag(), u.type(), u.position(), hpLeft, u.maxHealth(),
                        shieldsLeft, u.maxShields(), 0, 0);
    }

    /** Package-private for testing — linear interpolation toward target. */
    static Point2d stepToward(Point2d from, Point2d to, double speed) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist <= speed) return to;
        return new Point2d(
            (float)(from.x() + dx * speed / dist),
            (float)(from.y() + dy * speed / dist));
    }

    static double distance(Point2d a, Point2d b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    public GameState snapshot() {
        // Fog filtering is only active when terrain is configured.
        // Without terrain (mock/test contexts) all enemies are visible as before.
        // Fog gated on terrainGrid: recompute(null terrain) treats all tiles as LOW and
        // produces selective visibility (only tiles within probe range are VISIBLE), which
        // would make distant enemies invisible in no-terrain test contexts. When no terrain
        // is configured, return all enemies as pre-fog behaviour.

        // Stamp weapon cooldown from unitCooldowns map onto each friendly unit.
        // friendly.units always carries weaponCooldownTicks=0; the true cooldown lives in unitCooldowns.
        List<Unit> friendlyWithCooldown = friendly.units.stream()
            .map(u -> new Unit(u.tag(), u.type(), u.position(),
                               u.health(), u.maxHealth(), u.shields(), u.maxShields(),
                               friendly.unitCooldowns.getOrDefault(u.tag(), 0),
                               friendly.blinkCooldowns.getOrDefault(u.tag(), 0)))
            .toList();

        if (terrainGrid != null) {
            List<Unit> visibleEnemies = enemy.units.stream()
                .filter(u -> visibility.isVisible(u.position()))
                .toList();
            List<Unit> visibleStaging = enemy.stagingArea.stream()
                .filter(u -> visibility.isVisible(u.position()))
                .toList();
            return new GameState(
                (int) friendly.minerals,
                friendly.vespene, friendly.supply, friendly.supplyUsed,
                friendlyWithCooldown, List.copyOf(friendly.buildings),
                visibleEnemies,
                List.copyOf(enemy.buildings),
                visibleStaging,
                List.copyOf(geysers),
                List.of(),   // mineralPatches: not modelled in emulated physics
                gameFrame);
        }
        return new GameState(
            (int) friendly.minerals, // floor: fractional minerals accumulate silently
            friendly.vespene, friendly.supply, friendly.supplyUsed,
            friendlyWithCooldown, List.copyOf(friendly.buildings),
            List.copyOf(enemy.units),
            List.copyOf(enemy.buildings),
            List.copyOf(enemy.stagingArea),
            List.copyOf(geysers),
            List.of(),   // mineralPatches: not modelled in emulated physics
            gameFrame);
    }

    private GameState snapshotForEnemy() {
        // Enemy sees friendly units as their "enemies"
        return new GameState(0, 0, 0, 0,
            List.of(), List.of(),
            List.copyOf(friendly.units), List.of(), List.of(),
            List.of(), List.of(), gameFrame);
    }

    /** Returns the current visibility grid — used by EmulatedEngine to update VisibilityHolder. */
    VisibilityGrid observeVisibility() { return visibility; }

    // --- Package-private: called by EmulatedEngine ---

    /** Set unit movement speed in tiles/tick. Called by EmulatedEngine each tick for live config. */
    void setUnitSpeed(double speed) { this.unitSpeed = speed; }

    /** Configure the enemy wave. Call before reset() — pendingWaves survives reset(). */
    void configureWave(long spawnFrame, int unitCount, UnitType unitType) {
        pendingWaves.clear();
        List<UnitType> types = Collections.nCopies(unitCount, unitType);
        pendingWaves.add(new EnemyWave(
            spawnFrame,
            new ArrayList<>(types),
            EnemyBehavior.STAGING_POS,
            new Point2d(8, 8)
        ));
    }

    // --- Package-private: used by EmulatedGameTest ---

    /** Sets mining probe counts per base. Package-private for tests; public for validation harnesses. */
    public void setMiningProbesPerBase(int... probesPerBase) {
        this.miningProbesPerBase = probesPerBase;
    }

    /** Wires an EnemyStrategy through an EnemyBehavior — test shim for retreat tests.
     *  Uses a permissive TechTree so existing tests are not affected by tech-tree gating. */
    void setEnemyStrategy(EnemyStrategy s) {
        if (s == null) {
            this.enemyBehavior = null;
        } else {
            TechTree permissive = new TechTree() {
                @Override public boolean canTrain(UnitType u, Set<BuildingType> b) { return true; }
                @Override public Optional<BuildingType> nextRequired(UnitType u, Set<BuildingType> b) { return Optional.empty(); }
            };
            this.enemyBehavior = new EnemyBehavior(s, enemy, permissive);
        }
    }

    /** Sets the EnemyBehavior directly — for tests that need full control. */
    void setEnemyBehavior(EnemyBehavior b) { this.enemyBehavior = b; }

    int  enemyMinerals()  { return (int) enemy.minerals; }
    int  enemyStagingSize() { return enemy.stagingArea.size(); }

    /** Direct mineral override for tests — avoids tick-based accumulation. */
    void setMineralsForTesting(int amount) { friendly.minerals = amount; }

    /** Positions an enemy near the base for combat tests. */
    void spawnEnemyForTesting(UnitType type, Point2d position) {
        int hp = SC2Data.maxHealth(type);
        String tag = "test-enemy-" + nextTag++;
        enemy.units.add(new Unit(tag, type, position, hp, hp,
            SC2Data.maxShields(type), SC2Data.maxShields(type), 0, 0));
        enemy.unitTargets.put(tag, EnemyBehavior.NEXUS_POS);
        enemy.attackingUnits.add(tag);  // test-spawned enemies are in attack mode by default
    }

    /** Sets a friendly unit's health for combat threshold tests. */
    void setHealthForTesting(String tag, int health) {
        friendly.units.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), health, u.maxHealth(), u.shields(), u.maxShields(), 0, 0)
            : u);
    }

    /** Sets a friendly unit's shields for shield absorption tests. */
    void setShieldsForTesting(String tag, int shields) {
        friendly.units.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), u.health(), u.maxHealth(), shields, u.maxShields(), 0, 0)
            : u);
    }

    /** Spawns a friendly unit at a specific position for combat tests. Returns the unit's tag. */
    String spawnFriendlyForTesting(UnitType type, Point2d position) {
        String tag = "test-unit-" + nextTag++;
        int hp = SC2Data.maxHealth(type);
        friendly.units.add(new Unit(tag, type, position, hp, hp,
            SC2Data.maxShields(type), SC2Data.maxShields(type), 0, 0));
        return tag;
    }

    /** Sets an enemy unit's shields directly — for testing Hardened Shield at 0 shields. */
    void setEnemyShieldsForTesting(String tag, int shields) {
        enemy.units.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), u.health(), u.maxHealth(),
                       shields, u.maxShields(), 0, 0)
            : u);
    }

    /** Returns a copy of retreating unit tags — for E6 retreat assertions. */
    Set<String> retreatingUnitTags() {
        return enemyBehavior != null ? Set.copyOf(enemyBehavior.retreatingUnits()) : Set.of();
    }

    /** Sets initialAttackSize directly — simulates a wave having been launched. */
    void setInitialAttackSizeForTesting(int n) {
        if (enemyBehavior != null) enemyBehavior.setInitialAttackSizeForTesting(n);
    }

    /** Sets an enemy unit's health directly — for retreat health threshold tests. */
    void setEnemyHealthForTesting(String tag, int health) {
        enemy.units.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), health, u.maxHealth(),
                       u.shields(), u.maxShields(), 0, 0)
            : u);
    }

    /** Swap movement strategy — used by pathfinding tests. Default is DirectMovement. */
    void setMovementStrategy(MovementStrategy s) { this.movementStrategy = s; }
    void setTerrainGrid(TerrainGrid g) { this.terrainGrid = g; }

    /** Injects a predictable Random for miss-chance tests. */
    void setRandomForTesting(Random r) { this.random = r; }

    /** Adds a unit directly to enemy staging area — for fog-of-war staging visibility tests. */
    void addStagedUnitForTesting(UnitType type, Point2d position) {
        String tag = "test-staging-" + nextTag++;
        int hp = SC2Data.maxHealth(type);
        enemy.stagingArea.add(new Unit(tag, type, position, hp, hp,
            SC2Data.maxShields(type), SC2Data.maxShields(type), 0, 0));
    }

    /** Test helper — adds a friendly unit directly to friendly.units. Package-private. */
    void spawnFriendlyUnitForTesting(UnitType type, Point2d position) {
        int hp  = SC2Data.maxHealth(type);
        int sh  = SC2Data.maxShields(type);
        String tag = "test-friendly-" + nextTag++;
        friendly.units.add(new Unit(tag, type, position, hp, hp, sh, sh, 0, 0));
    }

    /** Adds a complete friendly building — for tests that need a specific building to train from. */
    Building spawnBuildingForTesting(BuildingType type, Point2d position) {
        String tag = "bldg-test-" + nextTag++;
        Building b = new Building(tag, type, position,
            SC2Data.maxBuildingHealth(type), SC2Data.maxBuildingHealth(type), true);
        friendly.buildings.add(b);
        return b;
    }

    /** Adds a complete enemy building — for tests that seed enemy production infrastructure. */
    void spawnEnemyBuildingForTesting(BuildingType type, Point2d position) {
        String tag = "enemy-bldg-test-" + nextTag++;
        int hp = SC2Data.maxBuildingHealth(type);
        enemy.buildings.add(new Building(tag, type, position, hp, hp, true));
    }

    /** Sets friendly supply caps — for tests that need more than the 3 default free supply. */
    void setSupplyForTesting(int supply, int supplyUsed) {
        friendly.supply     = supply;
        friendly.supplyUsed = supplyUsed;
    }

    /**
     * Sets the supply cap from replay ground truth.
     * The real player builds Pylons that EmulatedGame cannot reconstruct from game events;
     * syncing supply from GT prevents the initial 15-supply cap from blocking training.
     * Does not change supplyUsed — only expands or contracts the cap.
     * Public: called from ReplayValidationHarness in a different package.
     */
    public void setSupplyCapForHarness(int supply) {
        friendly.supply = supply;
    }

    /**
     * Sets the friendly vespene pool from replay ground truth.
     * Mirrors the vespene the real player had available at this tick,
     * so gas-unit TrainIntents are not rejected by the resource check.
     * Public: called from ReplayValidationHarness in a different package.
     */
    public void setVespeneForHarness(int vespene) {
        friendly.vespene = vespene;
    }

    /**
     * Injects a building directly into the friendly player state without resource deduction.
     * Used by ReplayValidationHarness for buildings that are gifted at game start (not
     * purchased), e.g. the initial Nexus. The real player never spent minerals on these,
     * so no cost deduction is correct.
     */
    public void injectReplayBuilding(Building building) {
        friendly.buildings.add(building);
    }

    /**
     * Injects a building into the friendly player state and deducts its mineral cost.
     * Used by ReplayValidationHarness for buildings ordered during the game.
     * Minerals may go negative when EM's model-approximated balance is below the real
     * player's balance at injection time. The debt is repaid through mining income over
     * the next few ticks — this correctly blocks training during the player's
     * mineral-constrained period without flooring EM at 0.
     */
    public void injectReplayBuildingWithCost(Building building) {
        friendly.minerals -= SC2Data.mineralCost(building.type());
        friendly.buildings.add(building);
    }

    /**
     * Updates an existing friendly building to complete status.
     * Used by ReplayValidationHarness when a building finishes construction in the replay ground truth.
     */
    public void markReplayBuildingComplete(String tag) {
        friendly.buildings.replaceAll(b -> b.tag().equals(tag)
            ? new Building(b.tag(), b.type(), b.position(), b.health(), b.maxHealth(), true)
            : b);
    }

}
