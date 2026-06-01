package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;

import java.util.*;

/**
 * Internal simulation state not exposed to external observers: production queues,
 * movement targets, and combat cooldowns. Contains both production scheduling state
 * (buildingQueues, pendingCompletions, buildingTrainingUntil, buildingCompletionAtLoop)
 * and movement/combat state (unitTargets, unitCooldowns, blinkCooldowns) — grouped here
 * because both categories are EmulatedGame internals that race model plugins never touch.
 *
 * <p>A PendingCompletion lambda captures the PlayerState and PhysicsState pair that was
 * in scope when it was registered. Never transfer a PendingCompletion between physics
 * objects — it references a specific player's pair.
 */
class PhysicsState {

    // Movement and combat
    final Map<String, Point2d> unitTargets    = new HashMap<>();
    final Map<String, Integer> unitCooldowns  = new HashMap<>();
    final Map<String, Integer> blinkCooldowns = new HashMap<>();

    // Production machinery
    final Map<String, Deque<UnitType>> buildingQueues           = new HashMap<>();
    final Map<String, Long>            buildingTrainingUntil    = new HashMap<>();
    final Map<String, Long>            buildingCompletionAtLoop = new HashMap<>();

    // In-flight completion callbacks
    final List<PendingCompletion> pendingCompletions = new ArrayList<>();

    void fireCompletions(long currentTick) {
        pendingCompletions.removeIf(item -> {
            if (item.completesAtTick() > currentTick) return false;
            item.action().run();
            return true;
        });
    }

    void clear() {
        unitTargets.clear();
        unitCooldowns.clear();
        blinkCooldowns.clear();
        buildingQueues.clear();
        buildingTrainingUntil.clear();
        buildingCompletionAtLoop.clear();
        pendingCompletions.clear();
    }

    record PendingCompletion(long completesAtTick, Runnable action) {}
}
