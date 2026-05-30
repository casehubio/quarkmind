package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * RaceModel implementation for Terran. Manages SCV worker income, MULE calldown
 * and expiry, and Terran initial state seeding.
 */
class TerranRaceModel implements RaceModel {

    static final int INITIAL_WORKERS = 12;

    // MULE expiry tracking: mule unit tag → absolute game loop at which it expires
    private final Map<String, Long> muleExpiresAtLoop = new HashMap<>();

    // Current game loop — updated in tickPassive, read in canProduce for MULE registration
    private long currentGameLoop;

    @Override
    public void seedInitialState(final PlayerState state, final List<Resource> geysers) {
        muleExpiresAtLoop.clear();
        currentGameLoop = 0;

        state.minerals   = SC2Data.INITIAL_MINERALS;
        state.vespene    = SC2Data.INITIAL_VESPENE;
        state.supply     = SC2Data.INITIAL_SUPPLY;
        state.supplyUsed = SC2Data.INITIAL_SUPPLY_USED;

        for (int i = 0; i < INITIAL_WORKERS; i++) {
            final int hp = SC2Data.maxHealth(UnitType.SCV);
            state.units.add(new Unit("scv-" + i, UnitType.SCV,
                new Point2d(9 + i * 0.5f, 9),
                hp, hp, 0, 0, 0, 0));
        }
        state.buildings.add(new Building("cc-0", BuildingType.COMMAND_CENTER,
            new Point2d(8, 8),
            SC2Data.maxBuildingHealth(BuildingType.COMMAND_CENTER),
            SC2Data.maxBuildingHealth(BuildingType.COMMAND_CENTER),
            true));

        geysers.add(new Resource("geyser-0", new Point2d(5, 11), 2250));
        geysers.add(new Resource("geyser-1", new Point2d(11, 5), 2250));
    }

    @Override
    public void tickPassive(final PlayerState state, final long gameLoop) {
        currentGameLoop = gameLoop;

        // Expire MULEs — collect first to avoid ConcurrentModificationException
        final List<String> expired = new ArrayList<>();
        muleExpiresAtLoop.forEach((tag, expiresAt) -> {
            if (gameLoop >= expiresAt) expired.add(tag);
        });
        expired.forEach(tag -> {
            muleExpiresAtLoop.remove(tag);
            state.units.removeIf(u -> u.tag().equals(tag));
        });

        // Add flat income for active MULEs
        if (!muleExpiresAtLoop.isEmpty()) {
            state.minerals += muleExpiresAtLoop.size() * SC2Data.muleIncomePerTick();
        }
    }

    @Override
    public ProductionResult canProduce(final PlayerState state, final String buildingTag,
                                       final UnitType unitType) {
        if (unitType == UnitType.MULE) {
            // Find Orbital Command position for spawn
            final Building oc = state.buildings.stream()
                .filter(b -> b.tag().equals(buildingTag) && b.isComplete())
                .findFirst().orElse(null);
            if (oc == null) return ProductionResult.BLOCKED;

            // Spawn MULE immediately (no queue, no resource cost)
            final String muleTag = "mule-" + buildingTag + "-" + currentGameLoop;
            final int hp = SC2Data.maxHealth(UnitType.MULE);
            state.units.add(new Unit(muleTag, UnitType.MULE, oc.position(),
                hp, hp, 0, 0, 0, 0));
            muleExpiresAtLoop.put(muleTag, currentGameLoop + SC2Data.MULE_LIFETIME_LOOPS);
            return ProductionResult.HANDLED;
        }
        return ProductionResult.PROCEED;
    }

    @Override
    public void onProductionCommitted(final PlayerState state, final String buildingTag,
                                      final UnitType unitType, final Supplier<String> tagSupplier) {
        // no-op for Terran non-MULE units
    }

    @Override
    public void onUnitSpawned(final PlayerState state, final UnitType type,
                              final String unitTag, final String buildingTag) {
        // no-op
    }

    private static final Set<BuildingType> TOWN_HALLS =
        Set.of(BuildingType.COMMAND_CENTER, BuildingType.ORBITAL_COMMAND,
               BuildingType.PLANETARY_FORTRESS);

    @Override
    public UnitType workerType() { return UnitType.SCV; }

    @Override
    public Set<BuildingType> townHallTypes() { return TOWN_HALLS; }

    // Package-private for testing
    int activeMuleCount() { return muleExpiresAtLoop.size(); }
}
