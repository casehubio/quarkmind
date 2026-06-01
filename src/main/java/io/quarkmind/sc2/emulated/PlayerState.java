package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Per-player mutable game state: units, buildings, and resources. Public so that
 * external {@link RaceModel} implementations (loaded from outside this package) can
 * read and mutate the state they need.
 *
 * <p>Public methods are semantic operations meaningful to a race plugin.
 * Package-private methods ({@code replaceAllUnits}, {@code removeUnitsWhere},
 * {@code replaceAllBuildings}, {@code clear}) are bulk structural operations used
 * only by EmulatedGame's physics engine.
 *
 * <p>Not thread-safe — all access is from the single game-tick scheduler thread.
 */
public class PlayerState {

    private final List<Unit>     units     = new ArrayList<>();
    private final List<Building> buildings = new ArrayList<>();
    private double minerals;
    private int    vespene;
    private int    supply;
    private int    supplyUsed;

    // --- Public typed API ---

    public void setMinerals(double m)        { this.minerals = m; }
    public void addMinerals(double amount)   { this.minerals += amount; }
    public void deductMinerals(double cost)  { this.minerals -= cost; }
    public double minerals()                 { return minerals; }

    public void setVespene(int v)            { this.vespene = v; }
    public void deductVespene(int cost)      { this.vespene -= cost; }
    public int  vespene()                    { return vespene; }

    public void setSupply(int s)             { this.supply = s; }
    public void addSupply(int amount)        { this.supply += amount; }
    public int  supply()                     { return supply; }

    public void setSupplyUsed(int s)         { this.supplyUsed = s; }
    public void addSupplyUsed(int cost)      { this.supplyUsed += cost; }
    public int  supplyUsed()                 { return supplyUsed; }

    public void addUnit(Unit unit)           { units.add(unit); }
    public void removeUnit(String tag)       { units.removeIf(u -> u.tag().equals(tag)); }
    public List<Unit> units()               { return Collections.unmodifiableList(units); }

    public void addBuilding(Building b)      { buildings.add(b); }
    public List<Building> buildings()       { return Collections.unmodifiableList(buildings); }

    // --- Package-private bulk ops for EmulatedGame physics ---

    List<Unit> removeUnitsWhere(Predicate<Unit> pred) {
        List<Unit> removed = new ArrayList<>();
        units.removeIf(u -> {
            if (pred.test(u)) { removed.add(u); return true; }
            return false;
        });
        return List.copyOf(removed);
    }

    void replaceAllUnits(UnaryOperator<Unit> op)         { units.replaceAll(op); }
    void replaceAllBuildings(UnaryOperator<Building> op) { buildings.replaceAll(op); }

    void clear() {
        units.clear();
        buildings.clear();
        minerals   = 0;
        vespene    = 0;
        supply     = 0;
        supplyUsed = 0;
    }
}
