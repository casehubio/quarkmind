package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PlayerStateTest {

    @Test
    void initialState_hasNoUnitsOrBuildings() {
        PlayerState s = new PlayerState();
        assertThat(s.units()).isEmpty();
        assertThat(s.buildings()).isEmpty();
    }

    @Test
    void minerals_defaultsToZero() {
        assertThat(new PlayerState().minerals()).isZero();
    }

    @Test
    void clear_resetsAllState() {
        PlayerState s = new PlayerState();
        s.setMinerals(500);
        s.setVespene(100);
        s.setSupply(22);
        s.setSupplyUsed(10);
        s.addUnit(new Unit("u1", UnitType.ZEALOT, new Point2d(1,1), 100,100,50,50,0,0));
        s.addBuilding(new Building("b1", BuildingType.GATEWAY, new Point2d(2,2), 500,500,true));
        s.clear();
        assertThat(s.minerals()).isZero();
        assertThat(s.vespene()).isZero();
        assertThat(s.supply()).isZero();
        assertThat(s.supplyUsed()).isZero();
        assertThat(s.units()).isEmpty();
        assertThat(s.buildings()).isEmpty();
    }

    // --- Public API tests ---

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
