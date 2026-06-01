package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // --- New public API tests ---

    @Test
    void addUnit_appendsAndUnits_returnsUnmodifiableView() {
        PlayerState s = new PlayerState();
        Unit u = new Unit("u1", UnitType.ZEALOT, new Point2d(1,1), 100,100,50,50,0,0);
        s.addUnit(u);
        assertThat(s.units()).containsExactly(u);
        assertThatThrownBy(() -> s.units().add(u))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void removeUnit_byTag_removesCorrectUnit() {
        PlayerState s = new PlayerState();
        s.addUnit(new Unit("u1", UnitType.ZEALOT, new Point2d(1,1), 100,100,50,50,0,0));
        s.addUnit(new Unit("u2", UnitType.ZEALOT, new Point2d(2,2), 100,100,50,50,0,0));
        s.removeUnit("u1");
        assertThat(s.units()).extracting(Unit::tag).containsExactly("u2");
    }

    @Test
    void addBuilding_appendsAndBuildings_returnsUnmodifiableView() {
        PlayerState s = new PlayerState();
        Building b = new Building("b1", BuildingType.NEXUS, new Point2d(8,8), 900,900,true);
        s.addBuilding(b);
        assertThat(s.buildings()).containsExactly(b);
        assertThatThrownBy(() -> s.buildings().add(b))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void setMinerals_and_minerals() {
        PlayerState s = new PlayerState();
        s.setMinerals(150.0);
        assertThat(s.minerals()).isEqualTo(150.0);
    }

    @Test
    void addMinerals_accumulates() {
        PlayerState s = new PlayerState();
        s.setMinerals(100.0);
        s.addMinerals(25.5);
        assertThat(s.minerals()).isEqualTo(125.5);
    }

    @Test
    void deductMinerals_reduces() {
        PlayerState s = new PlayerState();
        s.setMinerals(100.0);
        s.deductMinerals(50.0);
        assertThat(s.minerals()).isEqualTo(50.0);
    }

    @Test
    void setVespene_and_deductVespene() {
        PlayerState s = new PlayerState();
        s.setVespene(200);
        s.deductVespene(75);
        assertThat(s.vespene()).isEqualTo(125);
    }

    @Test
    void setSupply_and_addSupply() {
        PlayerState s = new PlayerState();
        s.setSupply(15);
        s.addSupply(8);
        assertThat(s.supply()).isEqualTo(23);
    }

    @Test
    void setSupplyUsed_and_addSupplyUsed() {
        PlayerState s = new PlayerState();
        s.setSupplyUsed(10);
        s.addSupplyUsed(2);
        assertThat(s.supplyUsed()).isEqualTo(12);
    }

    @Test
    void removeUnitsWhere_returnsRemovedUnitsAndRemovesThem() {
        PlayerState s = new PlayerState();
        Unit dead  = new Unit("d1", UnitType.ZEALOT, new Point2d(1,1), 0,100,50,50,0,0);
        Unit alive = new Unit("a1", UnitType.ZEALOT, new Point2d(2,2), 100,100,50,50,0,0);
        s.addUnit(dead);
        s.addUnit(alive);
        List<Unit> removed = s.removeUnitsWhere(u -> u.health() <= 0);
        assertThat(removed).containsExactly(dead);
        assertThat(s.units()).containsExactly(alive);
    }

    @Test
    void replaceAllUnits_updatesUnitsInPlace() {
        PlayerState s = new PlayerState();
        s.addUnit(new Unit("u1", UnitType.ZEALOT, new Point2d(1,1), 100,100,50,50,0,0));
        s.replaceAllUnits(u -> new Unit(u.tag(), u.type(), new Point2d(5,5),
            u.health(), u.maxHealth(), u.shields(), u.maxShields(), 0, 0));
        assertThat(s.units().get(0).position()).isEqualTo(new Point2d(5,5));
    }

    @Test
    void replaceAllBuildings_updatesBuildings() {
        PlayerState s = new PlayerState();
        s.addBuilding(new Building("b1", BuildingType.NEXUS, new Point2d(8,8), 900,900,false));
        s.replaceAllBuildings(b -> b.tag().equals("b1")
            ? new Building(b.tag(), b.type(), b.position(), b.health(), b.maxHealth(), true)
            : b);
        assertThat(s.buildings().get(0).isComplete()).isTrue();
    }
}
