package io.quarkmind.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SC2 tech tree prerequisite graph for all three races.
 * Maps unit types to the ordered list of buildings that must exist before training.
 *
 * TECH_LAB is an add-on structure, not modelled as a BuildingType — Terran units
 * that require it (Marauder) are mapped to their primary building prerequisite only.
 *
 * Not thread-safe — all access is expected from the single game-tick scheduler thread.
 */
public class TechTree {

    private static final Map<UnitType, List<BuildingType>> PREREQUISITES =
        new EnumMap<>(UnitType.class);

    static {
        // --- Protoss ---
        req(UnitType.ZEALOT,   BuildingType.GATEWAY);
        req(UnitType.STALKER,  BuildingType.GATEWAY, BuildingType.CYBERNETICS_CORE);
        req(UnitType.ADEPT,    BuildingType.GATEWAY, BuildingType.CYBERNETICS_CORE);
        req(UnitType.IMMORTAL, BuildingType.ROBOTICS_FACILITY);
        req(UnitType.COLOSSUS, BuildingType.ROBOTICS_FACILITY, BuildingType.ROBOTICS_BAY);
        req(UnitType.OBSERVER, BuildingType.ROBOTICS_FACILITY);
        req(UnitType.PHOENIX,  BuildingType.STARGATE);
        req(UnitType.VOID_RAY, BuildingType.STARGATE);
        req(UnitType.ORACLE,   BuildingType.STARGATE);
        req(UnitType.CARRIER,  BuildingType.STARGATE, BuildingType.FLEET_BEACON);
        req(UnitType.TEMPEST,  BuildingType.STARGATE, BuildingType.FLEET_BEACON);

        // --- Terran ---
        // TECH_LAB is an add-on, not a standalone BuildingType — Marauder mapped to BARRACKS only.
        req(UnitType.MARINE,   BuildingType.BARRACKS);
        req(UnitType.MARAUDER, BuildingType.BARRACKS);
        req(UnitType.HELLION,  BuildingType.FACTORY);
        req(UnitType.MEDIVAC,  BuildingType.STARPORT);
        req(UnitType.VIKING,   BuildingType.STARPORT);

        // --- Zerg ---
        req(UnitType.ZERGLING,  BuildingType.SPAWNING_POOL);
        req(UnitType.ROACH,     BuildingType.ROACH_WARREN);
        req(UnitType.HYDRALISK, BuildingType.HYDRALISK_DEN);
        req(UnitType.MUTALISK,  BuildingType.SPIRE);
        req(UnitType.ULTRALISK, BuildingType.ULTRALISK_CAVERN);
        req(UnitType.OVERLORD,  BuildingType.HATCHERY);
        req(UnitType.OVERSEER,  BuildingType.HATCHERY, BuildingType.LAIR);
        // PROBE, SCV, DRONE: no prerequisites — absent from map, defaults to empty
    }

    private static void req(UnitType unit, BuildingType... buildings) {
        PREREQUISITES.put(unit, List.of(buildings));
    }

    /**
     * Returns true if all prerequisite buildings for {@code unit} are present in {@code built}.
     * Units not in the prerequisite map (e.g. PROBE, SCV, DRONE) return true unconditionally.
     */
    public boolean canTrain(UnitType unit, Set<BuildingType> built) {
        return built.containsAll(PREREQUISITES.getOrDefault(unit, List.of()));
    }

    /**
     * Returns the first prerequisite building missing from {@code built} for {@code unit},
     * or empty if all prerequisites are satisfied (or the unit has none).
     */
    public Optional<BuildingType> nextRequired(UnitType unit, Set<BuildingType> built) {
        return PREREQUISITES.getOrDefault(unit, List.of()).stream()
            .filter(b -> !built.contains(b))
            .findFirst();
    }
}
