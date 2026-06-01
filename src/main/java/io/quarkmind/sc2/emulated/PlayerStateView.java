package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Building;
import io.quarkmind.domain.Unit;

import java.util.List;

public interface PlayerStateView {
    double minerals();
    int vespene();
    int supply();
    int supplyUsed();
    List<Unit> units();
    List<Building> buildings();
}
