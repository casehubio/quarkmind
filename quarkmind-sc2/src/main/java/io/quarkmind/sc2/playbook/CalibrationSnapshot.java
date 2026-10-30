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
    Set<String> upgrades
) {
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
            state.supplyUsed(),
            unitCounts,
            buildingCounts,
            new TreeSet<>(state.playerUpgrades())
        );
    }
}
