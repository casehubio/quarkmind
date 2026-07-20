package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import java.util.Set;
import java.util.stream.Collectors;

public record ExpansionPlacement(
    LocationReference targetExpansion,
    double proximityRadius,
    Set<String> baselineBaseTags
) implements VerificationPredicate {

    static final Set<BuildingType> TOWN_HALL_TYPES = Set.of(
        BuildingType.NEXUS,
        BuildingType.COMMAND_CENTER, BuildingType.ORBITAL_COMMAND, BuildingType.PLANETARY_FORTRESS,
        BuildingType.HATCHERY, BuildingType.LAIR, BuildingType.HIVE
    );

    @Override
    public VerificationPredicate withBaseline(GameState state, LocationResolver resolver) {
        Set<String> tags = state.myBuildings().stream()
            .filter(b -> TOWN_HALL_TYPES.contains(b.type()))
            .map(b -> b.tag())
            .collect(Collectors.toUnmodifiableSet());
        return new ExpansionPlacement(targetExpansion, proximityRadius, tags);
    }

    @Override
    public boolean isSatisfied(GameState state, LocationResolver resolver) {
        Point2d target = resolver.resolve(targetExpansion, state);
        if (target == null) return false;

        return state.myBuildings().stream()
            .filter(b -> TOWN_HALL_TYPES.contains(b.type()))
            .filter(b -> !baselineBaseTags.contains(b.tag()))
            .anyMatch(b -> b.position().distanceTo(target) <= proximityRadius);
    }
}
