package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * RaceModel implementation for Zerg. Manages larva regeneration per hatchery,
 * EGG unit lifecycle, Queen energy regeneration, and automatic inject.
 *
 * <p>Larva is modelled as a counter (not a unit entity). EGG is a unit entity
 * visible in the snapshot — see Known Divergences in the spec.
 */
class ZergRaceModel implements RaceModel {

    static final int INITIAL_WORKERS   = 12;
    static final int INITIAL_SUPPLY    = 14; // HATCHERY(6) + OVERLORD(8)
    static final int MAX_LARVA         = 3;
    static final int MAX_INJECT_LARVA  = 19; // SC2 cap with injected larva
    static final long LARVA_REGEN_LOOPS = 245L;
    static final double INJECT_COST_ENERGY = 25.0;
    static final int    INJECT_LARVA_COUNT = 4;

    // Per-hatchery state
    private final Map<String, Integer> hatcheryLarvaCount    = new HashMap<>();
    private final Map<String, Long>    hatcheryNextLarvaLoop = new HashMap<>();

    // EGG tracking: building tag → FIFO queue of egg unit tags (multiple in-flight per hatchery)
    private final Map<String, Deque<String>> eggTagByBuilding = new HashMap<>();

    // Queen energy: queen unit tag → current energy (named queenEnergyMap to avoid shadowing the accessor method)
    private final Map<String, Double>  queenEnergyMap        = new HashMap<>();

    private long currentGameLoop;

    @Override
    public void seedInitialState(final PlayerState state, final List<Resource> geysers) {
        // Clear all internal state for reset safety
        hatcheryLarvaCount.clear();
        hatcheryNextLarvaLoop.clear();
        eggTagByBuilding.clear();
        queenEnergyMap.clear();
        currentGameLoop = 0;

        state.minerals   = SC2Data.INITIAL_MINERALS;
        state.vespene    = SC2Data.INITIAL_VESPENE;
        state.supply     = INITIAL_SUPPLY;
        state.supplyUsed = SC2Data.INITIAL_SUPPLY_USED;

        for (int i = 0; i < INITIAL_WORKERS; i++) {
            final int hp = SC2Data.maxHealth(UnitType.DRONE);
            state.units.add(new Unit("drone-" + i, UnitType.DRONE,
                new Point2d(9 + i * 0.5f, 9),
                hp, hp, 0, 0, 0, 0));
        }

        final String hatcheryTag = "hatchery-0";
        state.buildings.add(new Building(hatcheryTag, BuildingType.HATCHERY,
            new Point2d(8, 8),
            SC2Data.maxBuildingHealth(BuildingType.HATCHERY),
            SC2Data.maxBuildingHealth(BuildingType.HATCHERY),
            true));
        hatcheryLarvaCount.put(hatcheryTag, MAX_LARVA);

        final int overlordHp = SC2Data.maxHealth(UnitType.OVERLORD);
        state.units.add(new Unit("overlord-0", UnitType.OVERLORD,
            new Point2d(14, 14),
            overlordHp, overlordHp, 0, 0, 0, 0));

        geysers.add(new Resource("geyser-0", new Point2d(5, 11), 2250));
        geysers.add(new Resource("geyser-1", new Point2d(11, 5), 2250));
    }

    @Override
    public void tickPassive(final PlayerState state, final long gameLoop) {
        currentGameLoop = gameLoop;

        // Larva regeneration — one per hatchery per 245 loops (capped at MAX_LARVA)
        for (final Building b : state.buildings) {
            if (!townHallTypes().contains(b.type()) || !b.isComplete()) continue;
            final String tag = b.tag();
            final int count = hatcheryLarvaCount.getOrDefault(tag, 0);
            if (count >= MAX_LARVA) continue;
            final long nextLoop = hatcheryNextLarvaLoop.getOrDefault(tag, 0L);
            if (gameLoop >= nextLoop) {
                hatcheryLarvaCount.put(tag, count + 1);
                hatcheryNextLarvaLoop.put(tag, gameLoop + LARVA_REGEN_LOOPS);
            }
        }

        // Queen energy regeneration
        for (final Unit u : state.units) {
            if (u.type() != UnitType.QUEEN) continue;
            final double energy = queenEnergyMap.getOrDefault(u.tag(), INJECT_COST_ENERGY);
            queenEnergyMap.put(u.tag(), Math.min(200.0,
                energy + SC2Data.QUEEN_ENERGY_REGEN_PER_LOOP * SC2Data.LOOPS_PER_TICK));
        }

        // Queen auto-inject: inject whenever energy >= threshold
        for (final Unit queen : state.units) {
            if (queen.type() != UnitType.QUEEN) continue;
            final double energy = queenEnergyMap.getOrDefault(queen.tag(), 0.0);
            if (energy < INJECT_COST_ENERGY) continue;
            // Find nearest hatchery
            final Building nearest = nearestTownHall(queen.position(), state.buildings);
            if (nearest == null) continue;
            final String tag = nearest.tag();
            final int current = hatcheryLarvaCount.getOrDefault(tag, 0);
            hatcheryLarvaCount.put(tag, Math.min(MAX_INJECT_LARVA, current + INJECT_LARVA_COUNT));
            queenEnergyMap.put(queen.tag(), energy - INJECT_COST_ENERGY);
        }
    }

    private Building nearestTownHall(final Point2d pos, final List<Building> buildings) {
        Building nearest = null;
        double minDist = Double.MAX_VALUE;
        for (final Building b : buildings) {
            if (!townHallTypes().contains(b.type()) || !b.isComplete()) continue;
            final double dx = pos.x() - b.position().x();
            final double dy = pos.y() - b.position().y();
            final double d = dx * dx + dy * dy;
            if (d < minDist) { minDist = d; nearest = b; }
        }
        return nearest;
    }

    @Override
    public ProductionResult canProduce(final PlayerState state, final String buildingTag,
                                       final UnitType unitType) {
        if (hatcheryLarvaCount.getOrDefault(buildingTag, 0) > 0) return ProductionResult.PROCEED;
        return ProductionResult.BLOCKED;
    }

    @Override
    public void onProductionCommitted(final PlayerState state, final String buildingTag,
                                      final UnitType unitType, final Supplier<String> tagSupplier) {
        // Consume larva
        hatcheryLarvaCount.merge(buildingTag, -1, Integer::sum);

        // Spawn EGG at hatchery position
        final Building hatchery = state.buildings.stream()
            .filter(b -> b.tag().equals(buildingTag))
            .findFirst().orElse(null);
        final Point2d eggPos = (hatchery != null) ? hatchery.position() : new Point2d(9, 9);
        final String eggTag = tagSupplier.get();
        final int eggHp = SC2Data.maxHealth(UnitType.EGG);
        state.units.add(new Unit(eggTag, UnitType.EGG, eggPos, eggHp, eggHp, 0, 0, 0, 0));
        eggTagByBuilding.computeIfAbsent(buildingTag, k -> new ArrayDeque<>()).add(eggTag);
    }

    @Override
    public void onUnitSpawned(final PlayerState state, final UnitType type,
                              final String unitTag, final String buildingTag) {
        // Remove the oldest pending EGG from this building (FIFO)
        final Deque<String> eggs = eggTagByBuilding.get(buildingTag);
        if (eggs != null) {
            final String eggTag = eggs.poll();
            if (eggTag != null) state.units.removeIf(u -> u.tag().equals(eggTag));
            if (eggs.isEmpty()) eggTagByBuilding.remove(buildingTag);
        }

        // Supply from Overlord
        if (type == UnitType.OVERLORD) {
            state.supply += 8;
        }

        // Register new Queen with starting energy
        if (type == UnitType.QUEEN) {
            queenEnergyMap.put(unitTag, INJECT_COST_ENERGY);
        }
    }

    @Override
    public int trainCount(final UnitType type) {
        return type == UnitType.ZERGLING ? 2 : 1;
    }

    private static final Set<BuildingType> TOWN_HALLS =
        Set.of(BuildingType.HATCHERY, BuildingType.LAIR, BuildingType.HIVE);

    @Override
    public UnitType workerType() { return UnitType.DRONE; }

    @Override
    public Set<BuildingType> townHallTypes() { return TOWN_HALLS; }

    // Package-private accessors and mutators for testing
    int larvaCount(final String hatcheryTag) {
        return hatcheryLarvaCount.getOrDefault(hatcheryTag, 0);
    }

    double queenEnergy(final String queenTag) {
        return queenEnergyMap.getOrDefault(queenTag, 0.0);
    }

    void setQueenEnergyForTesting(final String queenTag, final double energy) {
        queenEnergyMap.put(queenTag, energy);
    }

    boolean eggPendingForBuilding(final String buildingTag) {
        final Deque<String> q = eggTagByBuilding.get(buildingTag);
        return q != null && !q.isEmpty();
    }
}
