package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PhysicsStateTest {

    @Test
    void fireCompletions_runsActionAtCorrectTick() {
        PhysicsState s = new PhysicsState();
        boolean[] fired = {false};
        s.pendingCompletions.add(new PhysicsState.PendingCompletion(5L, () -> fired[0] = true));
        s.fireCompletions(4L);
        assertThat(fired[0]).isFalse();
        s.fireCompletions(5L);
        assertThat(fired[0]).isTrue();
        assertThat(s.pendingCompletions).isEmpty();
    }

    @Test
    void fireCompletions_doesNotFireFutureCompletions() {
        PhysicsState s = new PhysicsState();
        boolean[] fired = {false};
        s.pendingCompletions.add(new PhysicsState.PendingCompletion(10L, () -> fired[0] = true));
        s.fireCompletions(5L);
        assertThat(fired[0]).isFalse();
        assertThat(s.pendingCompletions).hasSize(1);
    }

    @Test
    void fireCompletions_firesMultipleCompletionsAtSameTick() {
        PhysicsState s = new PhysicsState();
        int[] count = {0};
        s.pendingCompletions.add(new PhysicsState.PendingCompletion(3L, () -> count[0]++));
        s.pendingCompletions.add(new PhysicsState.PendingCompletion(3L, () -> count[0]++));
        s.fireCompletions(3L);
        assertThat(count[0]).isEqualTo(2);
        assertThat(s.pendingCompletions).isEmpty();
    }

    @Test
    void clear_resetsAllFields() {
        PhysicsState s = new PhysicsState();
        s.unitTargets.put("u1", new Point2d(1, 1));
        s.unitCooldowns.put("u1", 3);
        s.blinkCooldowns.put("u1", 2);
        s.buildingQueues.put("b1", new java.util.ArrayDeque<>());
        s.buildingTrainingUntil.put("b1", 10L);
        s.buildingCompletionAtLoop.put("b1", 220L);
        s.pendingCompletions.add(new PhysicsState.PendingCompletion(999L, () -> {}));
        s.clear();
        assertThat(s.unitTargets).isEmpty();
        assertThat(s.unitCooldowns).isEmpty();
        assertThat(s.blinkCooldowns).isEmpty();
        assertThat(s.buildingQueues).isEmpty();
        assertThat(s.buildingTrainingUntil).isEmpty();
        assertThat(s.buildingCompletionAtLoop).isEmpty();
        assertThat(s.pendingCompletions).isEmpty();
    }
}
