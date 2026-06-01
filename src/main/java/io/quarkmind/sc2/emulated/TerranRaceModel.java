package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import java.util.*;
import java.util.function.Supplier;

class TerranRaceModel implements RaceModel {

    static final int INITIAL_WORKERS = 12;

    private final Map<String, Long> muleExpiresAtLoop = new HashMap<>();
    private long currentGameLoop;

    @Override
    public void seedInitialState(final PlayerState state, final List<Resource> geysers) {
        muleExpiresAtLoop.clear();
        currentGameLoop = 0;

        state.setMinerals(SC2Data.INITIAL_MINERALS);
        state.setVespene(SC2Data.INITIAL_VESPENE);
        state.setSupply(SC2Data.INITIAL_SUPPLY);
        state.setSupplyUsed(SC2Data.INITIAL_SUPPLY_USED);

        for (int i = 0; i < INITIAL_WORKERS; i++) {
            final int hp = SC2Data.maxHealth(UnitType.SCV);
            state.addUnit(new Unit("scv-" + i, UnitType.SCV,
                new Point2d(9 + i * 0.5f, 9), hp, hp, 0, 0, 0, 0));
        }
        state.addBuilding(new Building("cc-0", BuildingType.COMMAND_CENTER,
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

        final List<String> expired = new ArrayList<>();
        muleExpiresAtLoop.forEach((tag, expiresAt) -> {
            if (gameLoop >= expiresAt) expired.add(tag);
        });
        expired.forEach(tag -> {
            muleExpiresAtLoop.remove(tag);
            state.removeUnit(tag);
        });

        if (!muleExpiresAtLoop.isEmpty()) {
            state.addMinerals(muleExpiresAtLoop.size() * SC2Data.muleIncomePerTick());
        }
    }

    @Override
    public ProductionResult canProduce(final PlayerState state, final String buildingTag,
                                       final UnitType unitType) {
        if (unitType == UnitType.MULE) {
            final Building oc = state.buildings().stream()
                .filter(b -> b.tag().equals(buildingTag) && b.isComplete())
                .findFirst().orElse(null);
            if (oc == null) return ProductionResult.BLOCKED;

            final String muleTag = "mule-" + buildingTag + "-" + currentGameLoop;
            final int hp = SC2Data.maxHealth(UnitType.MULE);
            state.addUnit(new Unit(muleTag, UnitType.MULE, oc.position(),
                hp, hp, 0, 0, 0, 0));
            muleExpiresAtLoop.put(muleTag, currentGameLoop + SC2Data.MULE_LIFETIME_LOOPS);
            return ProductionResult.HANDLED;
        }
        return ProductionResult.PROCEED;
    }

    @Override
    public void onProductionCommitted(final PlayerState state, final String buildingTag,
                                      final UnitType unitType, final Supplier<String> tagSupplier) {}

    @Override
    public void onUnitSpawned(final PlayerState state, final UnitType type,
                              final String unitTag, final String buildingTag) {}

    private static final Set<BuildingType> TOWN_HALLS =
        Set.of(BuildingType.COMMAND_CENTER, BuildingType.ORBITAL_COMMAND,
               BuildingType.PLANETARY_FORTRESS);

    @Override public UnitType workerType()             { return UnitType.SCV; }
    @Override public Set<BuildingType> townHallTypes() { return TOWN_HALLS; }

    int activeMuleCount() { return muleExpiresAtLoop.size(); }
}
