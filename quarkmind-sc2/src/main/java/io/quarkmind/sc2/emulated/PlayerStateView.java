package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.Unit;

import java.util.List;

/**
 * Read-only projection of {@link PlayerState} for use in {@link RaceModel#canProduce}.
 * Structural enforcement: callers holding only a {@code PlayerStateView} cannot call
 * any mutator on the underlying state. {@link PlayerState} implements this interface.
 */
public interface PlayerStateView {
    double minerals();
    int vespene();
    int supply();
    int supplyUsed();
    List<Unit> units();
    List<Building> buildings();
}
