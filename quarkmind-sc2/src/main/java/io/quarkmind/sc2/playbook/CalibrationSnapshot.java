package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.GameState;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public record CalibrationSnapshot(
        int tick,
        int minerals,
        int vespene,
        int supply,
        int supplyUsed,
        Map<String, Integer> units,
        Map<String, Integer> buildings,
        Set<String> upgrades,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        int[] workersPerBase
) {
    public CalibrationSnapshot(int tick, int minerals, int vespene, int supply, int supplyUsed,
                               Map<String, Integer> units, Map<String, Integer> buildings,
                               Set<String> upgrades) {
        this(tick, minerals, vespene, supply, supplyUsed, units, buildings, upgrades, null);
    }

    public static CalibrationSnapshot from(int tick, GameState state) {
        Map<String, Integer> unitCounts = state.myUnits().stream()
                                               .filter(u -> u.type() != null)
                                               .collect(Collectors.groupingBy(
                                                       u -> u.type().name(),
                                                       TreeMap::new,
                                                       Collectors.summingInt(u -> 1)));

        Map<String, Integer> buildingCounts = state.myBuildings().stream()
                                                   .filter(Building::isComplete)
                                                   .filter(b -> b.type() != null)
                                                   .collect(Collectors.groupingBy(
                                                           b -> b.type().name(),
                                                           TreeMap::new,
                                                           Collectors.summingInt(b -> 1)));

        return new CalibrationSnapshot(
                tick,
                state.minerals(),
                state.vespene(),
                state.supply(),
                (int) state.supplyUsed(),
                unitCounts,
                buildingCounts,
                new TreeSet<>(state.playerUpgrades()),
                null
        );
    }

    public CalibrationSnapshot withWorkersPerBase(int[] perBase) {
        return new CalibrationSnapshot(tick, minerals, vespene, supply, supplyUsed,
                                       units, buildings, upgrades, perBase);
    }
}
