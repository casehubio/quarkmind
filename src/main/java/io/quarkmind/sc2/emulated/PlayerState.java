package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import java.util.*;

/**
 * Per-player mutable state for the emulated game. Holds units, buildings, resources,
 * pending build/train completions, and movement target maps. Both the friendly player
 * and the enemy player have their own instance inside EmulatedGame.
 *
 * Not thread-safe — all access is expected from the single game-tick scheduler thread.
 */
class PlayerState {

    record PendingCompletion(long completesAtTick, Runnable action) {}

    // Core lists
    final List<Unit>               units               = new ArrayList<>();
    final List<Building>           buildings           = new ArrayList<>();
    final List<Unit>               stagingArea         = new ArrayList<>();
    final List<PendingCompletion>  pendingCompletions  = new ArrayList<>();
    final Map<String, Deque<UnitType>> buildingQueues       = new HashMap<>();
    final Map<String, Long>            buildingTrainingUntil = new HashMap<>();
    final Map<String, Long>            buildingCompletionAtLoop = new HashMap<>();

    // Resources
    double minerals;
    int    vespene;
    int    supply;
    int    supplyUsed;

    // Movement / combat state (generalized — used for both players)
    final Map<String, Point2d> unitTargets    = new HashMap<>();
    final Map<String, Integer> unitCooldowns  = new HashMap<>();
    final Map<String, Integer> blinkCooldowns = new HashMap<>();

    void fireCompletions(long currentTick) {
        pendingCompletions.removeIf(item -> {
            if (item.completesAtTick() > currentTick) return false;
            item.action().run();
            return true;
        });
    }

    void clear() {
        units.clear();
        buildings.clear();
        stagingArea.clear();
        pendingCompletions.clear();
        buildingQueues.clear();
        buildingTrainingUntil.clear();
        buildingCompletionAtLoop.clear();
        unitTargets.clear();
        unitCooldowns.clear();
        blinkCooldowns.clear();
        minerals   = 0;
        vespene    = 0;
        supply     = 0;
        supplyUsed = 0;
    }
}
