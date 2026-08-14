package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.UnitType;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Plugin seam for race-specific game mechanics. Implementations own initial state seeding,
 * per-tick passive behaviour (larva regen, MULE expiry, Queen energy), and production
 * resource management. EmulatedGame remains race-agnostic.
 *
 * <p>Lives in {@code sc2.emulated} alongside {@link PlayerState}. {@code PlayerState} is
 * public (#164) — implementations outside this package may read and mutate it.
 * When #74 (pluggable races) arrives, implementations can move to external modules.
 *
 * <p>NOT thread-safe — all calls are from the single game-tick thread.
 */
public interface RaceModel {

    /**
     * Seeds the initial game state for this race. Called by EmulatedGame.reset() after clearing
     * player state. The model clears its own internal state before seeding.
     *
     * <p>Supply is seeded directly (e.g. Protoss: 15, Zerg: 14) — this is an intentional bypass
     * of the supplyBonus() / onUnitSpawned() path, consistent with how reset() worked before the
     * RaceModel refactor. Seeding is not normal game flow.
     */
    void seedInitialState(PlayerState state, List<Resource> geysers);

    /**
     * Passive per-tick mechanics: larva regeneration, MULE expiry and income, Queen energy
     * regen and auto-inject. Called by EmulatedGame.tick() after worker income is accumulated.
     *
     * @param gameLoop current absolute game loop (gameFrame × LOOPS_PER_TICK)
     */
    void tickPassive(PlayerState state, long gameLoop);

    /**
     * Query whether production can proceed for the given unit from the given building.
     * Called after building validation but BEFORE resource deduction.
     *
     * <p>The view is read-only by construction — structural enforcement replaces the
     * prior doc-only constraint. Return BLOCKED when a race-specific resource is
     * unavailable (e.g. no larva). Calldown abilities (e.g. MULE) route through
     * {@link #onCalldown} via {@code MuleCalldownIntent} — never through this method.
     *
     * @param view      read-only projection of player state — no mutation possible
     * @param buildingTag the building from which production is being attempted
     * @param unitType  the unit type to produce
     * @return PROCEED if resources are available (or not applicable for this race/unit),
     *         BLOCKED if a race-specific resource is unavailable
     */
    ProductionDecision canProduce(PlayerStateView view, String buildingTag, UnitType unitType);

    /**
     * Consume the race-specific production resource and perform any pre-spawn setup.
     * Called AFTER resource deduction succeeds (minerals, gas, supply already deducted).
     * For Zerg: decrements larva count and spawns the EGG unit.
     * For Protoss/Terran (non-MULE): no-op.
     *
     * @param tagSupplier provides the next unique tag from EmulatedGame (e.g. for EGG)
     */
    void onProductionCommitted(PlayerState state, String buildingTag, UnitType unitType,
                               Supplier<String> tagSupplier);

    /**
     * Called when a unit has finished training and is placed in state.units.
     * For Zerg: removes the EGG unit for this building, applies Overlord supply bonus.
     * For Terran: no-op (MULE spawning and expiry registration handled in onCalldown).
     *
     * @param buildingTag the building that produced the unit (lambda-captured in startTraining)
     */
    void onUnitSpawned(PlayerState state, UnitType type, String unitTag, String buildingTag);

    /**
     * Handle a direct calldown ability for this race (e.g. MULE calldown from Orbital Command).
     * Called by EmulatedGame after OC building validation succeeds.
     * May call addUnit/removeUnit on state and update model-internal state.
     * Must NOT manipulate resource fields (minerals, vespene, supply, supplyUsed).
     * Default: no-op.
     *
     * @param state     the per-player mutable game state — may call addUnit/removeUnit, must NOT touch resource fields
     * @param buildingTag the tag of the Orbital Command (or equivalent calldown building)
     * @param absLoop   absolute game loop (gameFrame × LOOPS_PER_TICK) when calldown was issued
     */
    default void onCalldown(PlayerState state, String buildingTag, long absLoop) {}

    /**
     * Number of units spawned from a single TrainIntent for this race.
     * Default is 1. ZergRaceModel overrides for ZERGLING (returns 2).
     */
    default int trainCount(UnitType type) {
        return 1;
    }

    /** The worker unit type for this race — used by countWorkersPerBase. */
    UnitType workerType();

    /** Town hall building types for this race — used by countWorkersPerBase. */
    Set<BuildingType> townHallTypes();
}
