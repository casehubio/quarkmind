package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class WorkerSimulation {

    record BaseEntry(String tag, Point2d position, List<MineralPatch> patches) {}

    private final List<BaseEntry> bases = new ArrayList<>();
    private final List<GasSlot> gasSlots = new ArrayList<>();
    private final Map<String, SimulatedWorker> workers = new LinkedHashMap<>();
    private final double workerDistancePerTick;

    WorkerSimulation() {
        double speed = SC2Data.unitSpeed(UnitType.PROBE);
        this.workerDistancePerTick = speed / SC2Data.GAME_LOOPS_PER_SECOND * SC2Data.LOOPS_PER_TICK;
    }

    void onTownHallCompleted(String tag, Point2d position) {
        List<MineralPatch> patches   = new ArrayList<>();
        int                baseIdx   = bases.size();
        Point2d[]          positions = MineralPatch.patchPositions(position);
        for (int i = 0; i < MineralPatch.PATCHES_PER_BASE; i++) {
            double amount = (i < MineralPatch.CLOSE_PATCHES_PER_BASE)
                            ? MineralPatch.CLOSE_PATCH_MINERALS
                            : MineralPatch.FAR_PATCH_MINERALS;
            patches.add(new MineralPatch(positions[i], baseIdx, amount));
        }
        bases.add(new BaseEntry(tag, position, patches));
    }

    void onTownHallCompleted(String tag, Point2d position, List<Resource> mineralResources) {
        List<MineralPatch> patches = new ArrayList<>();
        int                baseIdx = bases.size();
        for (Resource r : mineralResources) {
            patches.add(new MineralPatch(r.position(), baseIdx, r.remaining(), r.rich()));
        }
        bases.add(new BaseEntry(tag, position, patches));
    }


    void onInitialWorkers(List<String> tags, Point2d basePosition, String baseTag) {
        int baseIdx = findBaseIndex(baseTag);
        for (String workerTag : tags) {
            SimulatedWorker w = new SimulatedWorker(workerTag, basePosition, baseIdx);
            workers.put(workerTag, w);
            assignToNearestPatch(w);
        }
    }

    void onWorkerProduced(String tag, Point2d position) {
        int baseIdx = findNearestBaseIndex(position);
        if (baseIdx < 0) return;
        SimulatedWorker w = new SimulatedWorker(tag, position, baseIdx);
        workers.put(tag, w);
        assignToNearestPatch(w);
    }

    private GasSlot findUndersaturatedGasSlot(int baseIdx) {
        for (GasSlot slot : gasSlots) {
            if (slot.baseIndex == baseIdx && slot.assignedWorkers.size() < GasSlot.MAX_WORKERS) {
                return slot;
            }
        }
        return null;
    }

    void onGasBuildingCompleted(String tag, Point2d position) {
        if (gasSlots.stream().anyMatch(s -> s.position.distanceTo(position) < 5.0)) return;
        int baseIdx = findNearestBaseIndex(position);
        if (baseIdx < 0) return;
        GasSlot slot = new GasSlot(tag, position, baseIdx);
        gasSlots.add(slot);
        int transferred = 0;
        for (SimulatedWorker w : new ArrayList<>(workers.values())) {
            if (transferred >= GasSlot.MAX_WORKERS) break;
            if (w.baseIndex != baseIdx) continue;
            if (w.task != WorkerTask.MINING_MINERALS
                    && w.task != WorkerTask.RETURNING_MINERALS
                    && w.task != WorkerTask.IDLE) continue;
            if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
                w.assignedPatch.miners.remove(w);
            }
            w.assignedPatch = null;
            w.assignedGasSlot = slot;
            w.task = WorkerTask.HARVESTING_GAS;
            w.targetPosition = slot.position;
            slot.assignedWorkers.add(w);
            transferred++;
        }
    }

    void rebalanceGasWorkers() {
        for (GasSlot slot : gasSlots) {
            int needed = GasSlot.MAX_WORKERS - slot.assignedWorkers.size();
            if (needed <= 0) continue;
            int mineralWorkersAtBase = countMineralWorkersAtBase(slot.baseIndex);
            int available = mineralWorkersAtBase - MIN_MINERAL_WORKERS_BEFORE_GAS;
            if (available <= 0) continue;
            int toTransfer = Math.min(needed, available);
            for (SimulatedWorker w : new ArrayList<>(workers.values())) {
                if (toTransfer <= 0) break;
                if (w.baseIndex != slot.baseIndex) continue;
                if (w.assignedGasSlot != null) continue;
                if (w.task != WorkerTask.MINING_MINERALS
                        && w.task != WorkerTask.RETURNING_MINERALS
                        && w.task != WorkerTask.IDLE) continue;
                if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
                    w.assignedPatch.miners.remove(w);
                }
                w.assignedPatch = null;
                w.assignedGasSlot = slot;
                w.task = WorkerTask.HARVESTING_GAS;
                w.targetPosition = slot.position;
                slot.assignedWorkers.add(w);
                toTransfer--;
            }
        }
    }

    private static final int MIN_MINERAL_WORKERS_BEFORE_GAS = 8;

    void onGasBuildingDestroyed(String tag) {
        GasSlot slot = null;
        for (GasSlot s : gasSlots) {
            if (s.buildingTag.equals(tag)) {
                slot = s;
                break;
            }
        }
        if (slot == null) {return;}
        for (SimulatedWorker w : new ArrayList<>(slot.assignedWorkers)) {
            slot.removeWorker(w);
            w.assignedGasSlot = null;
            w.task            = WorkerTask.IDLE;
            assignToNearestPatch(w);
        }
        gasSlots.remove(slot);
    }


    void onWorkerDied(String tag) {
        SimulatedWorker w = workers.remove(tag);
        if (w == null) {return;}
        if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
            w.assignedPatch.miners.remove(w);
        }
        boolean wasGasWorker = w.assignedGasSlot != null;
        if (w.assignedGasSlot != null) {
            w.assignedGasSlot.removeWorker(w);
        }
        if (wasGasWorker) {
            rebalanceGasWorkers();
        }
    }

    TickIncome tick() {
        double mineralIncome = 0;
        double gasIncome = 0;
        for (SimulatedWorker w : workers.values()) {
            if (w.assignedGasSlot != null) {
                gasIncome += tickGasWorker(w);
            } else {
                mineralIncome += tickMineralWorker(w);
            }
        }
        return new TickIncome(mineralIncome, gasIncome);
    }

    private double tickMineralWorker(SimulatedWorker w) {
        if (w.task == WorkerTask.BUILDING || w.task == WorkerTask.IDLE) return 0;
        if (w.task == WorkerTask.HARVESTING_GAS || w.task == WorkerTask.RETURNING_GAS) return 0;

        if (w.task == WorkerTask.MINING_MINERALS) {
            if (w.assignedPatch == null) {
                w.task = WorkerTask.IDLE;
                return 0;
            }
            if (!w.isAtTarget()) {
                w.moveToward(workerDistancePerTick);
                if (w.isAtTarget()) {
                    tryStartMining(w);
                }
                return 0;
            }
            if (!w.assignedPatch.miners.contains(w)) {
                tryStartMining(w);
                return 0;
            }
            w.cycleTicksRemaining--;
            if (w.cycleTicksRemaining <= 0) {
                double cargo = w.assignedPatch.rich ? SimulatedWorker.RICH_MINERAL_CARGO : SimulatedWorker.MINERAL_CARGO;
                w.cargo = w.assignedPatch.harvest(cargo);
                w.assignedPatch.miners.remove(w);
                w.task = WorkerTask.RETURNING_MINERALS;
                w.targetPosition = bases.get(w.baseIndex).position();
            }
            return 0;
        }

        if (w.task == WorkerTask.RETURNING_MINERALS) {
            w.moveToward(workerDistancePerTick);
            if (w.isAtTarget()) {
                double deposited = w.cargo;
                w.cargo = 0;
                assignToNearestPatch(w);
                return deposited;
            }
            return 0;
        }

        return 0;
    }

    private void tryStartMining(SimulatedWorker w) {
        if (w.assignedPatch.miners.size() < MineralPatch.MAX_MINERS) {
            w.assignedPatch.miners.add(w);
            w.cycleTicksRemaining = SimulatedWorker.MINING_DURATION_TICKS;
        }
    }

    private double tickGasWorker(SimulatedWorker w) {
        GasSlot slot = w.assignedGasSlot;
        if (slot == null) {return 0;}

        if (w.task == WorkerTask.HARVESTING_GAS) {
            if (slot.insideWorkers.contains(w)) {
                w.cycleTicksRemaining--;
                if (w.cycleTicksRemaining <= 0) {
                    w.cargo = SimulatedWorker.GAS_CARGO;
                    slot.exitBuilding(w);
                    w.task           = WorkerTask.RETURNING_GAS;
                    w.targetPosition = bases.get(w.baseIndex).position();
                    w.moveToward(workerDistancePerTick);
                    if (w.isAtTarget()) {
                        double deposited = w.cargo;
                        w.cargo          = 0;
                        w.task           = WorkerTask.HARVESTING_GAS;
                        w.targetPosition = slot.position;
                        return deposited;
                    }
                }
                return 0;
            }
            w.moveToward(workerDistancePerTick);
            if (w.isAtTarget() && slot.canEnter()) {
                slot.enterBuilding(w);
            }
            return 0;
        }

        if (w.task == WorkerTask.RETURNING_GAS) {
            w.moveToward(workerDistancePerTick);
            if (w.isAtTarget()) {
                double deposited = w.cargo;
                w.cargo          = 0;
                w.task           = WorkerTask.HARVESTING_GAS;
                w.targetPosition = slot.position;
                return deposited;
            }
            return 0;
        }
        return 0;
    }

    private void assignToNearestPatch(SimulatedWorker w) {
        if (w.baseIndex < 0 || w.baseIndex >= bases.size()) {
            w.task = WorkerTask.IDLE;
            return;
        }
        BaseEntry base = bases.get(w.baseIndex);
        MineralPatch nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (MineralPatch p : base.patches()) {
            if (p.isDepleted()) continue;
            if (p.miners.size() >= MineralPatch.MAX_MINERS) continue;
            double d = w.position.distanceTo(p.position);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = p;
            }
        }

        if (nearest == null) {
            nearestDist = Double.MAX_VALUE;
            for (MineralPatch p : base.patches()) {
                if (p.isDepleted()) continue;
                double d = w.position.distanceTo(p.position);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = p;
                }
            }
        }

        if (nearest == null) {
            w.task = WorkerTask.IDLE;
            w.assignedPatch = null;
            return;
        }

        w.assignedPatch = nearest;
        w.targetPosition = nearest.position;
        w.task = WorkerTask.MINING_MINERALS;
        if (w.isAtTarget()) {
            if (nearest.miners.size() < MineralPatch.MAX_MINERS || nearest.miners.contains(w)) {
                if (!nearest.miners.contains(w)) nearest.miners.add(w);
                w.cycleTicksRemaining = SimulatedWorker.MINING_DURATION_TICKS;
            }
        }
    }

    record FormulaComparison(double simulatedMineral, double formulaMineral,
                             double simulatedGas, double formulaGas) {
        double mineralDivergence() {
            if (formulaMineral == 0) return simulatedMineral == 0 ? 0 : 1;
            return Math.abs(simulatedMineral - formulaMineral) / formulaMineral;
        }
        double gasDivergence() {
            if (formulaGas == 0) return simulatedGas == 0 ? 0 : 1;
            return Math.abs(simulatedGas - formulaGas) / formulaGas;
        }
    }

    FormulaComparison compareWithFormula(TickIncome simulated) {
        double formulaMineral = 0;
        int[] mineralCounts = mineralWorkersPerBase();
        for (int count : mineralCounts) {
            formulaMineral += SC2Data.mineralIncomePerTick(count);
        }
        double formulaGas = 0;
        for (GasSlot slot : gasSlots) {
            formulaGas += SC2Data.gasIncomePerTick(slot.assignedWorkers.size());
        }
        return new FormulaComparison(
                simulated.mineralIncome(), formulaMineral,
                simulated.vespeneIncome(), formulaGas);
    }

    TickIncome formulaIncome() {
        double formulaMineral = 0;
        int[]  mineralCounts  = mineralWorkersPerBase();
        for (int count : mineralCounts) {
            formulaMineral += SC2Data.mineralIncomePerTick(count);
        }
        double formulaGas = 0;
        for (GasSlot slot : gasSlots) {
            formulaGas += SC2Data.gasIncomePerTick(slot.assignedWorkers.size());
        }
        return new TickIncome(formulaMineral, formulaGas);
    }

    int gasSlotCount() {
        return gasSlots.size();
    }

    int totalGasWorkers() {
        return gasSlots.stream().mapToInt(s -> s.assignedWorkers.size()).sum();
    }


    int[] mineralWorkersPerBase() {
        int[] counts = new int[bases.size()];
        for (SimulatedWorker w : workers.values()) {
            if (w.task == WorkerTask.MINING_MINERALS || w.task == WorkerTask.RETURNING_MINERALS) {
                if (w.baseIndex >= 0 && w.baseIndex < counts.length) {
                    counts[w.baseIndex]++;
                }
            }
        }
        return counts;
    }

    void syncMineralWorkersFromGt(int[] mineralWorkersPerBase) {
        for (int baseIdx = 0; baseIdx < bases.size() && baseIdx < mineralWorkersPerBase.length; baseIdx++) {
            int target = mineralWorkersPerBase[baseIdx];
            int current = countMineralWorkersAtBase(baseIdx);
            if (current > target) {
                int excess = current - target;
                for (SimulatedWorker w : new ArrayList<>(workers.values())) {
                    if (excess <= 0) break;
                    if (w.baseIndex == baseIdx
                            && (w.task == WorkerTask.MINING_MINERALS || w.task == WorkerTask.RETURNING_MINERALS)) {
                        if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
                            w.assignedPatch.miners.remove(w);
                        }
                        w.assignedPatch = null;
                        w.task = WorkerTask.IDLE;
                        excess--;
                    }
                }
            } else if (current < target) {
                int needed = target - current;
                for (SimulatedWorker w : workers.values()) {
                    if (needed <= 0) break;
                    if (w.task == WorkerTask.IDLE) {
                        w.baseIndex = baseIdx;
                        assignToNearestPatch(w);
                        needed--;
                    }
                }
            }
        }
    }

    void syncGasWorkersFromGt(java.util.Map<String, Integer> gasWorkersPerBuilding) {
        for (GasSlot slot : gasSlots) {
            int target = gasWorkersPerBuilding.getOrDefault(slot.buildingTag, 0);
            int current = slot.assignedWorkers.size();
            if (current > target) {
                int excess = current - target;
                for (int i = 0; i < excess && !slot.assignedWorkers.isEmpty(); i++) {
                    SimulatedWorker w = slot.assignedWorkers.remove(slot.assignedWorkers.size() - 1);
                    slot.removeWorker(w);
                    w.assignedGasSlot = null;
                    w.task = WorkerTask.IDLE;
                    assignToNearestPatch(w);
                }
            } else if (current < target) {
                int needed = target - current;
                for (SimulatedWorker w : new ArrayList<>(workers.values())) {
                    if (needed <= 0) break;
                    if (w.baseIndex == slot.baseIndex
                            && (w.task == WorkerTask.MINING_MINERALS
                                || w.task == WorkerTask.RETURNING_MINERALS
                                || w.task == WorkerTask.IDLE)) {
                        if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
                            w.assignedPatch.miners.remove(w);
                        }
                        w.assignedPatch = null;
                        w.assignedGasSlot = slot;
                        w.task = WorkerTask.HARVESTING_GAS;
                        w.targetPosition = slot.position;
                        slot.assignedWorkers.add(w);
                        needed--;
                    }
                }
            }
        }
    }

    private int countMineralWorkersAtBase(int baseIdx) {
        int count = 0;
        for (SimulatedWorker w : workers.values()) {
            if (w.baseIndex == baseIdx
                    && (w.task == WorkerTask.MINING_MINERALS || w.task == WorkerTask.RETURNING_MINERALS)) {
                count++;
            }
        }
        return count;
    }

    void pauseAllWorkers() {
        for (SimulatedWorker w : workers.values()) {
            if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
                w.assignedPatch.miners.remove(w);
            }
            w.assignedPatch = null;
            if (w.assignedGasSlot != null) {
                w.assignedGasSlot.removeWorker(w);
                w.assignedGasSlot = null;
            }
            w.task = WorkerTask.IDLE;
        }
    }

    void reset() {
        bases.clear();
        gasSlots.clear();
        workers.clear();
    }

    private int findBaseIndex(String baseTag) {
        for (int i = 0; i < bases.size(); i++) {
            if (bases.get(i).tag().equals(baseTag)) return i;
        }
        return 0;
    }

    private int findNearestBaseIndex(Point2d pos) {
        if (bases.isEmpty()) return -1;
        int nearest = 0;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < bases.size(); i++) {
            double d = pos.distanceTo(bases.get(i).position());
            if (d < nearestDist) {
                nearestDist = d;
                nearest = i;
            }
        }
        return nearest;
    }
}
