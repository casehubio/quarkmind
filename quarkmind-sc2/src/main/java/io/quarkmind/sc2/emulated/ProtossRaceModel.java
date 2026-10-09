package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

class ProtossRaceModel implements RaceModel {

    private final Map<String, Double> nexusEnergyMap     = new HashMap<>();
    private final Map<String, Long>   chronoBoostedUntil = new HashMap<>();

    @Override
    public void seedInitialState(final PlayerState state, final List<Resource> geysers) {
        nexusEnergyMap.clear();
        chronoBoostedUntil.clear();

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
        nexusEnergyMap.put("nexus-0", SC2Data.NEXUS_STARTING_ENERGY);

        geysers.add(new Resource("geyser-0", new Point2d(5, 11), 2250));
        geysers.add(new Resource("geyser-1", new Point2d(11, 5), 2250));
    }

    @Override
    public void tickPassive(final PlayerState state, final long gameLoop) {
        for (final Building b : state.buildings()) {
            if (b.type() != BuildingType.NEXUS || !b.isComplete()) {continue;}
            double energy = nexusEnergyMap.getOrDefault(b.tag(), 0.0);
            nexusEnergyMap.put(b.tag(), Math.min(SC2Data.MAX_CASTER_ENERGY,
                                                 energy + SC2Data.NEXUS_ENERGY_REGEN_PER_LOOP * SC2Data.LOOPS_PER_TICK));
        }
    }

    @Override
    public boolean handleAbility(PlayerState state, String casterTag, String ability,
                                 String targetTag, long gameLoop) {
        if (!"CHRONO_BOOST".equals(ability)) {return false;}
        double energy = nexusEnergyMap.getOrDefault(casterTag, 0.0);
        if (energy < SC2Data.CHRONO_BOOST_ENERGY_COST) {return false;}
        nexusEnergyMap.put(casterTag, energy - SC2Data.CHRONO_BOOST_ENERGY_COST);
        chronoBoostedUntil.put(targetTag, gameLoop + SC2Data.CHRONO_BOOST_DURATION_LOOPS);
        return true;
    }

    @Override
    public double trainingSpeedMultiplier(String buildingTag, long gameLoop) {
        Long expiresAt = chronoBoostedUntil.get(buildingTag);
        if (expiresAt != null && gameLoop < expiresAt) {return SC2Data.CHRONO_BOOST_MULTIPLIER;}
        return 1.0;
    }

    @Override
    public void onBuildingComplete(PlayerState state, BuildingType type, String buildingTag) {
        if (type == BuildingType.NEXUS) {
            nexusEnergyMap.put(buildingTag, SC2Data.NEXUS_STARTING_ENERGY);
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

    private static final Set<BuildingType> TOWN_HALLS = Set.of(BuildingType.NEXUS);

    @Override
    public UnitType workerType()             {return UnitType.PROBE;}

    @Override
    public Set<BuildingType> townHallTypes() {return TOWN_HALLS;}
}
