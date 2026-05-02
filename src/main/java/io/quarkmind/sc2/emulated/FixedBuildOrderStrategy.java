package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import java.util.List;
import java.util.Optional;

public class FixedBuildOrderStrategy implements EnemyStrategy {

    private final String            name;
    private final Race              race;
    private final List<UnitType>    buildOrder;
    private final int               mineralsPerTick;
    private final EnemyAttackConfig attackConfig;
    private int buildIndex = 0;

    public FixedBuildOrderStrategy(String name, Race race, List<UnitType> buildOrder,
                                   int mineralsPerTick, EnemyAttackConfig attackConfig) {
        this.name            = name;
        this.race            = race;
        this.buildOrder      = List.copyOf(buildOrder);
        this.mineralsPerTick = mineralsPerTick;
        this.attackConfig    = attackConfig;
    }

    @Override public String            name()            { return name;            }
    @Override public Race              race()            { return race;            }
    @Override public int               mineralsPerTick() { return mineralsPerTick; }
    @Override public EnemyAttackConfig attackConfig()    { return attackConfig;    }

    @Override
    public Optional<UnitType> nextUnit(EnemyObservation obs) {
        if (buildOrder.isEmpty()) return Optional.empty();
        UnitType unit = buildOrder.get(buildIndex % buildOrder.size());
        buildIndex = (buildIndex + 1) % buildOrder.size();
        return Optional.of(unit);
    }

    @Override public boolean shouldSwitch(EnemyObservation obs) { return false; }

    @Override public void reset() { buildIndex = 0; }
}
