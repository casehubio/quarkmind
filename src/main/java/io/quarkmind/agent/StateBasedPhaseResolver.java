package io.quarkmind.agent;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PhaseResolver;

import java.util.EnumSet;
import java.util.Set;

public class StateBasedPhaseResolver implements PhaseResolver {

    static final double MID_TIME_FLOOR = 3.0;
    static final double LATE_TIME_FLOOR = 8.0;
    static final int MID_SUPPLY_THRESHOLD = 60;
    static final int LATE_SUPPLY_THRESHOLD = 150;
    static final int MID_EXPANSION_THRESHOLD = 2;
    static final int LATE_EXPANSION_THRESHOLD = 3;

    private static final Set<BuildingType> EXPANSION_TYPES = EnumSet.of(
        BuildingType.NEXUS,
        BuildingType.COMMAND_CENTER, BuildingType.ORBITAL_COMMAND, BuildingType.PLANETARY_FORTRESS,
        BuildingType.HATCHERY, BuildingType.LAIR, BuildingType.HIVE);

    private static final Set<BuildingType> TIER_2 = EnumSet.of(
        BuildingType.ROBOTICS_FACILITY, BuildingType.STARGATE, BuildingType.TWILIGHT_COUNCIL,
        BuildingType.FACTORY, BuildingType.STARPORT,
        BuildingType.LAIR, BuildingType.HYDRALISK_DEN, BuildingType.ROACH_WARREN);

    private static final Set<BuildingType> TIER_3 = EnumSet.of(
        BuildingType.FLEET_BEACON, BuildingType.ROBOTICS_BAY, BuildingType.TEMPLAR_ARCHIVES,
        BuildingType.DARK_SHRINE,
        BuildingType.FUSION_CORE, BuildingType.GHOST_ACADEMY,
        BuildingType.HIVE, BuildingType.GREATER_SPIRE, BuildingType.ULTRALISK_CAVERN);

    @Override
    public GamePhase resolve(GameState gameState) {
        double minutes = gameState.gameTimeMinutes();

        long expansions = gameState.myBuildings().stream()
            .filter(b -> EXPANSION_TYPES.contains(b.type()))
            .count();
        boolean hasTier2 = gameState.myBuildings().stream()
            .anyMatch(b -> TIER_2.contains(b.type()));
        boolean hasTier3 = gameState.myBuildings().stream()
            .anyMatch(b -> TIER_3.contains(b.type()));
        int supply = gameState.supplyUsed();

        GamePhase raw;
        if ((expansions >= LATE_EXPANSION_THRESHOLD && hasTier3) || supply >= LATE_SUPPLY_THRESHOLD) {
            raw = GamePhase.LATE;
        } else if (expansions >= MID_EXPANSION_THRESHOLD || hasTier2 || hasTier3 || supply >= MID_SUPPLY_THRESHOLD) {
            raw = GamePhase.MID;
        } else {
            raw = GamePhase.EARLY;
        }

        if (raw == GamePhase.LATE && minutes < LATE_TIME_FLOOR) return GamePhase.MID;
        if (raw == GamePhase.MID && minutes < MID_TIME_FLOOR) return GamePhase.EARLY;
        return raw;
    }
}
