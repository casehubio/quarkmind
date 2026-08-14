package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.GameState;

public sealed interface VerificationPredicate permits
    CountDelta,
    ArmyCentroidMovement,
    ExpansionPlacement,
    UnitsNearLocation {

    VerificationPredicate withBaseline(GameState state, LocationResolver resolver);
    boolean isSatisfied(GameState state, LocationResolver resolver);
}
