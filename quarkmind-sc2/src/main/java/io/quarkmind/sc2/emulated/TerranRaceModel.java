package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

class TerranRaceModel implements RaceModel {

    static final int INITIAL_WORKERS = 12;

    private final Map<String, Long>   muleExpiresAtLoop = new HashMap<>();
    private final Map<String, Double> ocEnergyMap       = new HashMap<>();

    @Override
    public void seedInitialState(final PlayerState state, final List<Resource> geysers) {
        muleExpiresAtLoop.clear();
        ocEnergyMap.clear();

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
        final List<String> expired = new ArrayList<>();
        muleExpiresAtLoop.forEach((tag, expiresAt) -> {
            if (gameLoop >= expiresAt) {expired.add(tag);}
        });
        expired.forEach(tag -> {
            muleExpiresAtLoop.remove(tag);
            state.removeUnit(tag);
        });

        if (!muleExpiresAtLoop.isEmpty()) {
            state.addMinerals(muleExpiresAtLoop.size() * SC2Data.muleIncomePerTick());
        }

        for (final Building b : state.buildings()) {
            if (b.type() != BuildingType.ORBITAL_COMMAND || !b.isComplete()) {continue;}
            double energy = ocEnergyMap.getOrDefault(b.tag(), 0.0);
            ocEnergyMap.put(b.tag(), Math.min(SC2Data.MAX_CASTER_ENERGY,
                                              energy + SC2Data.NEXUS_ENERGY_REGEN_PER_LOOP * SC2Data.LOOPS_PER_TICK));
        }
    }

    @Override
    public boolean handleAbility(PlayerState state, String casterTag, String ability,
                                 String targetTag, long gameLoop) {
        if (!"MULE_CALLDOWN".equals(ability)) {return false;}
        Building oc = state.buildings().stream()
                           .filter(b -> b.tag().equals(casterTag) && b.isComplete()
                                        && b.type() == BuildingType.ORBITAL_COMMAND)
                           .findFirst().orElse(null);
        if (oc == null) {return false;}
        double energy = ocEnergyMap.getOrDefault(casterTag, 0.0);
        if (energy < SC2Data.CHRONO_BOOST_ENERGY_COST) {return false;}
        ocEnergyMap.put(casterTag, energy - SC2Data.CHRONO_BOOST_ENERGY_COST);
        onCalldown(state, casterTag, gameLoop);
        return true;
    }

    @Override
    public void onBuildingComplete(PlayerState state, BuildingType type, String buildingTag) {
        if (type == BuildingType.ORBITAL_COMMAND) {
            ocEnergyMap.put(buildingTag, SC2Data.NEXUS_STARTING_ENERGY);
        }
    }

    @Override
    public ProductionDecision canProduce(final PlayerStateView view, final String buildingTag,
                                         final UnitType unitType) {
        return ProductionDecision.PROCEED;
    }

    @Override
    public void onProductionCommitted(final PlayerState state, final String buildingTag,
                                      final UnitType unitType, final Supplier<String> tagSupplier) {}

    @Override
    public void onUnitSpawned(final PlayerState state, final UnitType type,
                              final String unitTag, final String buildingTag) {}

    @Override
    public void onCalldown(final PlayerState state, final String buildingTag, final long absLoop) {
        final Building oc = state.buildings().stream()
                                 .filter(b -> b.tag().equals(buildingTag) && b.isComplete()
                                              && b.type() == BuildingType.ORBITAL_COMMAND)
                                 .findFirst().orElse(null);
        if (oc == null) {return;}
        final String muleTag = "mule-" + buildingTag + "-" + absLoop;
        final int    hp      = SC2Data.maxHealth(UnitType.MULE);
        state.addUnit(new Unit(muleTag, UnitType.MULE, oc.position(), hp, hp, 0, 0, 0, 0));
        muleExpiresAtLoop.put(muleTag, absLoop + SC2Data.MULE_LIFETIME_LOOPS);
    }

    private static final Set<BuildingType> TOWN_HALLS =
            Set.of(BuildingType.COMMAND_CENTER, BuildingType.ORBITAL_COMMAND,
                   BuildingType.PLANETARY_FORTRESS);

    @Override
    public UnitType workerType()             {return UnitType.SCV;}

    @Override
    public Set<BuildingType> townHallTypes() {return TOWN_HALLS;}

    int activeMuleCount()                    {return muleExpiresAtLoop.size();}
}
