package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import java.util.*;

/**
 * Static registry of named enemy strategies.
 * <p>
 * {@link #forName(String)} returns a fresh {@link FixedBuildOrderStrategy} instance
 * on every call, so each caller gets independent build-order state.
 * {@link #randomForRace(Race)} likewise returns a fresh instance.
 */
public final class EnemyStrategyLibrary {

    // Attack configs — (minWaveSize, mineralThreshold, mineralsPerUnit, maxWaveDelay)
    private static final EnemyAttackConfig FAST_PUSH =
        new EnemyAttackConfig(4, 150, 25, 40);
    private static final EnemyAttackConfig SLOW_PUSH =
        new EnemyAttackConfig(6, 250, 20, 30);
    private static final EnemyAttackConfig FLOOD =
        new EnemyAttackConfig(8, 120, 15, 25);

    /** Template records — one per named strategy. Never mutated; cloned on lookup. */
    private record Template(String name, Race race, List<UnitType> buildOrder,
                            int mineralsPerTick, EnemyAttackConfig attackConfig) {}

    private static final Map<String, Template> REGISTRY = new LinkedHashMap<>();

    static {
        add("PROTOSS_4GATE",        Race.PROTOSS, FAST_PUSH, 2,
            UnitType.ZEALOT, UnitType.ZEALOT, UnitType.STALKER, UnitType.STALKER,
            UnitType.STALKER, UnitType.STALKER);
        add("PROTOSS_BLINK_STALKER", Race.PROTOSS, SLOW_PUSH, 2,
            UnitType.STALKER, UnitType.STALKER, UnitType.STALKER, UnitType.STALKER);
        add("PROTOSS_COLOSSUS_PUSH", Race.PROTOSS, SLOW_PUSH, 2,
            UnitType.ZEALOT, UnitType.IMMORTAL, UnitType.IMMORTAL, UnitType.COLOSSUS);
        add("ZERG_ROACH_RUSH",       Race.ZERG,    FAST_PUSH, 3,
            UnitType.ROACH, UnitType.ROACH, UnitType.ROACH,
            UnitType.ROACH, UnitType.ROACH, UnitType.ROACH);
        add("ZERG_MASS_LING",        Race.ZERG,    FLOOD,     3,
            UnitType.ZERGLING, UnitType.ZERGLING, UnitType.ZERGLING, UnitType.ZERGLING,
            UnitType.ZERGLING, UnitType.ZERGLING, UnitType.ZERGLING, UnitType.ZERGLING);
        add("ZERG_HYDRA_SWITCH",     Race.ZERG,    SLOW_PUSH, 3,
            UnitType.ZERGLING, UnitType.ZERGLING, UnitType.ZERGLING, UnitType.ZERGLING,
            UnitType.HYDRALISK, UnitType.HYDRALISK, UnitType.HYDRALISK, UnitType.HYDRALISK);
        add("TERRAN_2RAX_MARINE",    Race.TERRAN,  FLOOD,     2,
            UnitType.MARINE, UnitType.MARINE, UnitType.MARINE, UnitType.MARINE,
            UnitType.MARINE, UnitType.MARINE, UnitType.MARINE, UnitType.MARINE);
        add("TERRAN_BIO_PUSH",       Race.TERRAN,  FAST_PUSH, 2,
            UnitType.MARINE, UnitType.MARINE, UnitType.MARINE, UnitType.MARINE,
            UnitType.MARAUDER, UnitType.MARAUDER, UnitType.MEDIVAC);
        add("TERRAN_MECH",           Race.TERRAN,  SLOW_PUSH, 2,
            UnitType.HELLION, UnitType.HELLION, UnitType.SIEGE_TANK, UnitType.SIEGE_TANK);
        // REACTIVE placeholder — replaced by ReactiveStrategy in a later task
        add("REACTIVE",              Race.PROTOSS, FAST_PUSH, 2,
            UnitType.ZEALOT, UnitType.STALKER);
    }

    private EnemyStrategyLibrary() {}

    private static void add(String name, Race race, EnemyAttackConfig atk,
                            int mpt, UnitType... units) {
        REGISTRY.put(name, new Template(name, race, List.of(units), mpt, atk));
    }

    /**
     * Returns a fresh strategy instance with build index at zero.
     * Each call produces a distinct object — callers may advance their instance
     * independently without affecting other callers.
     *
     * @throws IllegalArgumentException if the name is not registered
     */
    public static EnemyStrategy forName(String name) {
        Template t = REGISTRY.get(name);
        if (t == null) throw new IllegalArgumentException("Unknown strategy: " + name);
        return new FixedBuildOrderStrategy(t.name(), t.race(), t.buildOrder(),
                                           t.mineralsPerTick(), t.attackConfig());
    }

    /**
     * Returns a fresh strategy instance chosen at random from strategies of the given race,
     * excluding the REACTIVE placeholder.
     *
     * @throws IllegalStateException if no non-REACTIVE strategies exist for the race
     */
    public static EnemyStrategy randomForRace(Race race) {
        List<Template> pool = REGISTRY.values().stream()
            .filter(t -> t.race() == race && !t.name().equals("REACTIVE"))
            .toList();
        if (pool.isEmpty()) throw new IllegalStateException("No strategies for race: " + race);
        Template t = pool.get(new Random().nextInt(pool.size()));
        return new FixedBuildOrderStrategy(t.name(), t.race(), t.buildOrder(),
                                           t.mineralsPerTick(), t.attackConfig());
    }

    /** Returns an unmodifiable view of all registered strategy names, in insertion order. */
    public static Set<String> allNames() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }
}
