package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlayerStateTest {

    @Test
    void initialState_hasNoUnitsOrBuildings() {
        PlayerState s = new PlayerState();
        assertThat(s.units).isEmpty();
        assertThat(s.buildings).isEmpty();
        assertThat(s.stagingArea).isEmpty();
    }

    @Test
    void minerals_defaultsToZero() {
        assertThat(new PlayerState().minerals).isZero();
    }

    @Test
    void fireCompletions_runsActionAtCorrectTick() {
        PlayerState s = new PlayerState();
        boolean[] fired = {false};
        s.pendingCompletions.add(new PlayerState.PendingCompletion(5L, () -> fired[0] = true));
        s.fireCompletions(4L);
        assertThat(fired[0]).isFalse();
        s.fireCompletions(5L);
        assertThat(fired[0]).isTrue();
        assertThat(s.pendingCompletions).isEmpty();
    }

    @Test
    void fireCompletions_doesNotFireFutureCompletions() {
        PlayerState s = new PlayerState();
        boolean[] fired = {false};
        s.pendingCompletions.add(new PlayerState.PendingCompletion(10L, () -> fired[0] = true));
        s.fireCompletions(5L);
        assertThat(fired[0]).isFalse();
        assertThat(s.pendingCompletions).hasSize(1);
    }

    @Test
    void fireCompletions_firesMultipleCompletionsInSameTick() {
        PlayerState s = new PlayerState();
        int[] count = {0};
        s.pendingCompletions.add(new PlayerState.PendingCompletion(3L, () -> count[0]++));
        s.pendingCompletions.add(new PlayerState.PendingCompletion(3L, () -> count[0]++));
        s.fireCompletions(3L);
        assertThat(count[0]).isEqualTo(2);
        assertThat(s.pendingCompletions).isEmpty();
    }

    @Test
    void clear_resetsAllState() {
        PlayerState s = new PlayerState();
        s.minerals   = 500;
        s.vespene    = 100;
        s.supply     = 22;
        s.supplyUsed = 10;
        s.units.add(new Unit("u1", UnitType.ZEALOT, new Point2d(1,1), 100,100,50,50,0,0));
        s.buildings.add(new Building("b1", BuildingType.GATEWAY, new Point2d(2,2), 500,500,true));
        s.stagingArea.add(new Unit("u2", UnitType.ZEALOT, new Point2d(3,3), 100,100,50,50,0,0));
        s.unitTargets.put("u1", new Point2d(10, 10));
        s.unitCooldowns.put("u1", 5);
        s.blinkCooldowns.put("u1", 3);
        s.pendingCompletions.add(new PlayerState.PendingCompletion(999L, () -> {}));
        s.clear();
        assertThat(s.minerals).isZero();
        assertThat(s.vespene).isZero();
        assertThat(s.supply).isZero();
        assertThat(s.supplyUsed).isZero();
        assertThat(s.units).isEmpty();
        assertThat(s.buildings).isEmpty();
        assertThat(s.stagingArea).isEmpty();
        assertThat(s.unitTargets).isEmpty();
        assertThat(s.unitCooldowns).isEmpty();
        assertThat(s.blinkCooldowns).isEmpty();
        assertThat(s.pendingCompletions).isEmpty();
    }

    @Test
    void unitTargets_defaultsEmpty() {
        assertThat(new PlayerState().unitTargets).isEmpty();
    }
}
