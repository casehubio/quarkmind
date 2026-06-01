package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

class ProtossRaceModel implements RaceModel {

    @Override
    public void seedInitialState(final PlayerState state, final List<Resource> geysers) {
        state.setMinerals(SC2Data.INITIAL_MINERALS);
        state.setVespene(SC2Data.INITIAL_VESPENE);
        state.setSupply(SC2Data.INITIAL_SUPPLY);
        state.setSupplyUsed(SC2Data.INITIAL_SUPPLY_USED);

        for (int i = 0; i < SC2Data.INITIAL_PROBES; i++) {
            final int hp = SC2Data.maxHealth(UnitType.PROBE);
            state.addUnit(new Unit("probe-" + i, UnitType.PROBE,
                new Point2d(9 + i * 0.5f, 9),
                hp, hp, SC2Data.maxShields(UnitType.PROBE), SC2Data.maxShields(UnitType.PROBE), 0, 0));
        }
        state.addBuilding(new Building("nexus-0", BuildingType.NEXUS,
            new Point2d(8, 8),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            true));

        geysers.add(new Resource("geyser-0", new Point2d(5, 11), 2250));
        geysers.add(new Resource("geyser-1", new Point2d(11, 5), 2250));
    }

    @Override
    public void tickPassive(final PlayerState state, final long gameLoop) {}

    @Override
    public ProductionResult canProduce(final PlayerState state, final String buildingTag,
                                       final UnitType unitType) {
        return ProductionResult.PROCEED;
    }

    @Override
    public void onProductionCommitted(final PlayerState state, final String buildingTag,
                                      final UnitType unitType, final Supplier<String> tagSupplier) {}

    @Override
    public void onUnitSpawned(final PlayerState state, final UnitType type,
                              final String unitTag, final String buildingTag) {}

    private static final Set<BuildingType> TOWN_HALLS = Set.of(BuildingType.NEXUS);

    @Override public UnitType workerType()             { return UnitType.PROBE; }
    @Override public Set<BuildingType> townHallTypes() { return TOWN_HALLS; }
}
