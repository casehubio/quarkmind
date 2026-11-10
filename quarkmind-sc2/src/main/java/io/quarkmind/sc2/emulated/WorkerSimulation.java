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
    static final float TOWN_HALL_RADIUS     = 2.5f;
    static final float MINERAL_PATCH_RADIUS = 1.0f;

    static Point2d edgePoint(Point2d center, Point2d toward, float radius) {
        float dx   = toward.x() - center.x();
        float dy   = toward.y() - center.y();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.01f) {return center;}
        return new Point2d(center.x() + dx / dist * radius, center.y() + dy / dist * radius);
    }

    private Point2d returnTarget(SimulatedWorker w) {
        Point2d basePos  = bases.get(w.baseIndex).position();
        Point2d patchPos = w.assignedPatch != null ? w.assignedPatch.position : w.position;
        return edgePoint(basePos, patchPos, TOWN_HALL_RADIUS);
    }

    static boolean useEdgeToEdge = true;

    private Point2d miningTarget(MineralPatch patch, int baseIndex) {
        if (!useEdgeToEdge) {return patch.position;}
        return edgePoint(patch.position, bases.get(baseIndex).position(), MINERAL_PATCH_RADIUS);
    }

    private Point2d depositTarget(SimulatedWorker w) {
        if (!useEdgeToEdge) {return bases.get(w.baseIndex).position();}
        return returnTarget(w);
    }


    record BaseEntry(String tag, Point2d position, List<MineralPatch> patches) {}

    private final List<BaseEntry> bases = new ArrayList<>();
    private final List<GasSlot> gasSlots = new ArrayList<>();
    private final Map<String, SimulatedWorker> workers = new LinkedHashMap<>();
    private final double workerDistancePerTick;
    private double currentTickStartLoop;

    WorkerSimulation() {
        double speed = SC2Data.unitSpeed(UnitType.PROBE);
        this.workerDistancePerTick = speed / SC2Data.GAME_LOOPS_PER_SECOND * SC2Data.LOOPS_PER_TICK;
    }

    private static final int OPTIMAL_WORKERS_PER_BASE = MineralPatch.PATCHES_PER_BASE * MineralPatch.MAX_MINERS;

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
        MineralPatch.computeApproachDocking(patches, position);
        bases.add(new BaseEntry(tag, position, patches));
        rebalanceMineralWorkersAcrossBases();
    }

    void onTownHallCompleted(String tag, Point2d position, List<Resource> mineralResources) {
        List<MineralPatch> patches = new ArrayList<>();
        int                baseIdx = bases.size();
        for (Resource r : mineralResources) {
            patches.add(new MineralPatch(r.position(), baseIdx, r.remaining(), r.rich()));
        }
        MineralPatch.computeApproachDocking(patches, position);
        bases.add(new BaseEntry(tag, position, patches));
        rebalanceMineralWorkersAcrossBases();
    }

    static final int MAX_TRANSFER_PER_EXPANSION = 4;

    private void rebalanceMineralWorkersAcrossBases() {
        if (bases.size() < 2) return;
        int newBaseIdx = bases.size() - 1;

        int bestSrcIdx = -1;
        int bestSrcCount = 0;
        for (int i = 0; i < bases.size() - 1; i++) {
            int count = countMineralWorkersAtBase(i);
            if (count > bestSrcCount) {
                bestSrcCount = count;
                bestSrcIdx = i;
            }
        }
        if (bestSrcIdx < 0) return;

        int excess = bestSrcCount - MIN_MINERAL_WORKERS_BEFORE_GAS;
        if (excess <= 0) return;

        int toTransfer = Math.min(excess, MAX_TRANSFER_PER_EXPANSION);
        transferMineralWorkers(bestSrcIdx, newBaseIdx, toTransfer);
    }

    private int transferMineralWorkers(int fromBase, int toBase, int count) {
        int     transferred = 0;
        Point2d targetPos   = bases.get(toBase).position();
        for (SimulatedWorker w : new ArrayList<>(workers.values())) {
            if (transferred >= count) {break;}
            if (w.baseIndex != fromBase) {continue;}
            if (w.assignedGasSlot != null) {continue;}
            if (w.task != WorkerTask.MINING_MINERALS
                && w.task != WorkerTask.RETURNING_MINERALS
                && w.task != WorkerTask.IDLE) {continue;}
            if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
                w.assignedPatch.miners.remove(w);
            }
            w.assignedPatch  = null;
            w.baseIndex      = toBase;
            w.task           = WorkerTask.TRANSFERRING;
            w.targetPosition = targetPos;
            transferred++;
        }
        return transferred;
    }


    static final double INITIAL_WALK_DELAY_LOOPS = 40.0;

    void onInitialWorkers(List<String> tags, Point2d basePosition, String baseTag) {
        int baseIdx = findBaseIndex(baseTag);
        for (int i = 0; i < tags.size(); i++) {
            SimulatedWorker w = new SimulatedWorker(tags.get(i), basePosition, baseIdx);
            w.walkStartLoop = INITIAL_WALK_DELAY_LOOPS + i * SimulatedWorker.INITIAL_WORKER_STAGGER_LOOPS;
            workers.put(tags.get(i), w);
            assignToNearestPatch(w);
        }
    }

    void onWorkerProduced(String tag, Point2d position) {
        int baseIdx = findNearestBaseIndex(position);
        if (baseIdx < 0) {return;}
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
                    && w.task != WorkerTask.DEPOSITING_MINERALS
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
                        && w.task != WorkerTask.DEPOSITING_MINERALS
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

    TickIncome tick(double tickStartLoop) {
        this.currentTickStartLoop = tickStartLoop;
        double tickEndLoop = tickStartLoop + SC2Data.LOOPS_PER_TICK;

        for (SimulatedWorker w : new ArrayList<>(workers.values())) {
            if (w.task == WorkerTask.SCOUTING && tickEndLoop >= w.scoutReturnLoop) {
                onWorkerReturnFromScout(w.tag);
            }
        }

        for (SimulatedWorker w : workers.values()) {
            if (w.task == WorkerTask.TRANSFERRING) {
                tickTransferringWorker(w);
            }
        }

        double mineralIncome = 0;
        for (int loop = 0; loop < SimulatedWorker.LOOPS_PER_TICK; loop++) {
            double currentLoop = tickStartLoop + loop;
            this.currentTickStartLoop = currentLoop;

            for (SimulatedWorker w : workers.values()) {
                if (w.assignedGasSlot == null) {
                    mineralIncome += stepOneMineralLoop(w, currentLoop);
                }
            }

            resolveAllMineralCollisions();
        }

        this.currentTickStartLoop = tickStartLoop;

        for (SimulatedWorker w : workers.values()) {
            if (w.task == WorkerTask.MINING_MINERALS && w.isAtTarget()
                && w.assignedPatch != null && !w.assignedPatch.miners.contains(w)
                && w.assignedPatch.miners.size() < MineralPatch.MAX_MINERS
                && tickEndLoop >= w.assignedPatch.lockEndLoop) {
                w.assignedPatch.miners.add(w);
                w.phaseEndLoop = tickEndLoop + w.assignedPatch.approachDockingLoops + SimulatedWorker.MINING_DURATION_LOOPS;
            }
        }

        double gasIncome = 0;
        for (SimulatedWorker w : workers.values()) {
            if (w.assignedGasSlot != null) {
                gasIncome += tickGasWorker(w, tickStartLoop, tickEndLoop);
            }
        }
        return new TickIncome(mineralIncome, gasIncome);
    }

    private double stepOneMineralLoop(SimulatedWorker w, double currentLoop) {
        if (w.task == WorkerTask.BUILDING || w.task == WorkerTask.IDLE
            || w.task == WorkerTask.SCOUTING || w.task == WorkerTask.TRANSFERRING) {return 0;}
        if (w.task == WorkerTask.HARVESTING_GAS || w.task == WorkerTask.RETURNING_GAS
            || w.task == WorkerTask.DEPOSITING_GAS) {return 0;}

        double income = 0;
        boolean loopConsumed = false;

        while (!loopConsumed) {
            if (w.task == WorkerTask.MINING_MINERALS) {
                if (w.assignedPatch == null) {
                    w.task = WorkerTask.IDLE;
                    return income;
                }
                if (w.walkStartLoop > 0) {
                    if (currentLoop < w.walkStartLoop) {return income;}
                    w.walkStartLoop = 0;
                }
                if (!w.isAtTarget()) {
                    w.moveOneLoop();
                    loopConsumed = true;
                    if (w.isAtTarget()) {
                        tryStartMining(w, currentLoop);
                    }
                    continue;
                }
                if (!w.assignedPatch.miners.contains(w)) {
                    if (currentLoop < w.assignedPatch.lockEndLoop) {return income;}
                    tryStartMining(w, currentLoop);
                    if (!w.assignedPatch.miners.contains(w)) {return income;}
                    continue;
                }
                if (currentLoop >= w.phaseEndLoop) {
                    double cargo = w.assignedPatch.rich ? SimulatedWorker.RICH_MINERAL_CARGO : SimulatedWorker.MINERAL_CARGO;
                    w.cargo = w.assignedPatch.harvest(cargo);
                    w.assignedPatch.miners.remove(w);
                    w.assignedPatch.lockEndLoop = currentLoop + SimulatedWorker.PATCH_DOCKING_OVERHEAD_LOOPS;
                    w.task           = WorkerTask.RETURNING_MINERALS;
                    w.targetPosition = depositTarget(w);
                    continue;
                }
                return income;
            }

            if (w.task == WorkerTask.RETURNING_MINERALS) {
                boolean arrived = w.moveOneLoop();
                loopConsumed = true;
                if (arrived) {
                    income += w.cargo;
                    w.cargo = 0;
                    if (SimulatedWorker.DEPOSIT_DURATION_LOOPS <= 0) {
                        returnToPatchOrReassign(w);
                        return income;
                    }
                    w.task         = WorkerTask.DEPOSITING_MINERALS;
                    w.phaseEndLoop = currentLoop + SimulatedWorker.DEPOSIT_DURATION_LOOPS;
                }
                continue;
            }

            if (w.task == WorkerTask.DEPOSITING_MINERALS) {
                if (currentLoop >= w.phaseEndLoop) {
                    returnToPatchOrReassign(w);
                    continue;
                }
                return income;
            }

            return income;
        }
        return income;
    }

    private void resolveAllMineralCollisions() {
        List<SimulatedWorker> walking = new ArrayList<>();
        List<SimulatedWorker> stationary = new ArrayList<>();
        for (SimulatedWorker w : workers.values()) {
            if (w.assignedGasSlot != null) {continue;}
            if (w.task != WorkerTask.MINING_MINERALS && w.task != WorkerTask.RETURNING_MINERALS) {continue;}
            if (w.isAtTarget()) {
                stationary.add(w);
            } else {
                walking.add(w);
            }
        }

        for (int i = 0; i < walking.size(); i++) {
            SimulatedWorker a = walking.get(i);
            for (int j = i + 1; j < walking.size(); j++) {
                SimulatedWorker b = walking.get(j);
                double d = a.position.distanceTo(b.position);
                if (d >= SimulatedWorker.COLLISION_DIAMETER || d < 0.001) {continue;}

                float dx    = b.position.x() - a.position.x();
                float dy    = b.position.y() - a.position.y();
                float perpX = -dy / (float) d;
                float perpY = dx / (float) d;
                float dodge = (float) (SimulatedWorker.COLLISION_DIAMETER - d) * 0.5f;

                if (a.tag.hashCode() < b.tag.hashCode()) {
                    a.position = new Point2d(a.position.x() + perpX * dodge, a.position.y() + perpY * dodge);
                    b.position = new Point2d(b.position.x() - perpX * dodge, b.position.y() - perpY * dodge);
                } else {
                    a.position = new Point2d(a.position.x() - perpX * dodge, a.position.y() - perpY * dodge);
                    b.position = new Point2d(b.position.x() + perpX * dodge, b.position.y() + perpY * dodge);
                }

                a.currentSpeed *= SimulatedWorker.COLLISION_SPEED_FACTOR;
                b.currentSpeed *= SimulatedWorker.COLLISION_SPEED_FACTOR;
            }
        }

        for (SimulatedWorker w : walking) {
            for (SimulatedWorker s : stationary) {
                double d = w.position.distanceTo(s.position);
                if (d >= SimulatedWorker.COLLISION_DIAMETER || d < 0.001) {continue;}

                float dx    = s.position.x() - w.position.x();
                float dy    = s.position.y() - w.position.y();
                float perpX = -dy / (float) d;
                float perpY = dx / (float) d;
                float dodge = (float) (SimulatedWorker.COLLISION_DIAMETER - d) * 0.5f;

                if (w.tag.hashCode() < s.tag.hashCode()) {
                    w.position = new Point2d(w.position.x() + perpX * dodge, w.position.y() + perpY * dodge);
                } else {
                    w.position = new Point2d(w.position.x() - perpX * dodge, w.position.y() - perpY * dodge);
                }
                w.currentSpeed *= SimulatedWorker.COLLISION_SPEED_FACTOR;
            }
        }
    }

    private void tryStartMining(SimulatedWorker w, double currentLoop) {
        if (w.assignedPatch.miners.size() < MineralPatch.MAX_MINERS
            && currentLoop >= w.assignedPatch.lockEndLoop) {
            w.assignedPatch.miners.add(w);
            w.phaseEndLoop = currentLoop + w.assignedPatch.approachDockingLoops + SimulatedWorker.MINING_DURATION_LOOPS;
        }
    }

    private double tickGasWorker(SimulatedWorker w, double tickStartLoop, double tickEndLoop) {
        GasSlot slot = w.assignedGasSlot;
        if (slot == null) {return 0;}

        int    loopsPerTick   = SimulatedWorker.LOOPS_PER_TICK;
        int    loopsRemaining = loopsPerTick;
        double income         = 0;

        while (loopsRemaining > 0) {
            if (w.task == WorkerTask.HARVESTING_GAS) {
                if (slot.insideWorkers.contains(w)) {
                    double currentLoop = tickStartLoop + (loopsPerTick - loopsRemaining);
                    if (currentLoop >= w.phaseEndLoop) {
                        w.cargo = SimulatedWorker.GAS_CARGO;
                        slot.exitBuilding(w);
                        w.task           = WorkerTask.RETURNING_GAS;
                        w.targetPosition = bases.get(w.baseIndex).position();
                        continue;
                    }
                    int loopsUntilDone = (int) Math.ceil(w.phaseEndLoop - currentLoop);
                    if (loopsUntilDone < loopsRemaining) {
                        loopsRemaining -= loopsUntilDone;
                        w.cargo = SimulatedWorker.GAS_CARGO;
                        slot.exitBuilding(w);
                        w.task           = WorkerTask.RETURNING_GAS;
                        w.targetPosition = bases.get(w.baseIndex).position();
                        continue;
                    }
                    return income;
                }
                int used = w.moveLoops(loopsRemaining);
                loopsRemaining -= used;
                double currentLoopGas = tickStartLoop + (loopsPerTick - loopsRemaining);
                if (w.isAtTarget() && slot.canEnter(currentLoopGas)) {
                    double arrivalLoop = currentLoopGas;
                    slot.enterBuilding(w, arrivalLoop);
                    w.phaseEndLoop = arrivalLoop + SimulatedWorker.GAS_HARVEST_DURATION_LOOPS;
                    if (loopsRemaining <= 0) {return income;}
                    continue;
                }
                return income;
            }

            if (w.task == WorkerTask.RETURNING_GAS) {
                int used = w.moveLoops(loopsRemaining);
                loopsRemaining -= used;
                if (w.isAtTarget()) {
                    income += w.cargo;
                    w.cargo = 0;
                    if (SimulatedWorker.DEPOSIT_DURATION_LOOPS <= 0) {
                        w.task           = WorkerTask.HARVESTING_GAS;
                        w.targetPosition = slot.position;
                        if (loopsRemaining <= 0) {return income;}
                        continue;
                    }
                    double arrivalLoop = tickStartLoop + (loopsPerTick - loopsRemaining);
                    w.task         = WorkerTask.DEPOSITING_GAS;
                    w.phaseEndLoop = arrivalLoop + SimulatedWorker.DEPOSIT_DURATION_LOOPS;
                    if (loopsRemaining <= 0) {return income;}
                    continue;
                }
                return income;
            }

            if (w.task == WorkerTask.DEPOSITING_GAS) {
                double currentLoop = tickStartLoop + (loopsPerTick - loopsRemaining);
                if (currentLoop >= w.phaseEndLoop) {
                    w.task           = WorkerTask.HARVESTING_GAS;
                    w.targetPosition = slot.position;
                    if (loopsRemaining <= 0) {return income;}
                    continue;
                }
                int loopsUntilDone = (int) Math.ceil(w.phaseEndLoop - currentLoop);
                if (loopsUntilDone < loopsRemaining) {
                    loopsRemaining -= loopsUntilDone;
                    w.task           = WorkerTask.HARVESTING_GAS;
                    w.targetPosition = slot.position;
                    if (loopsRemaining <= 0) {return income;}
                    continue;
                }
                return income;
            }

            return income;
        }
        return income;
    }


    private int countAssignedWorkers(MineralPatch p, SimulatedWorker exclude) {
        int count = 0;
        for (SimulatedWorker other : workers.values()) {
            if (other != exclude && other.assignedPatch == p) {count++;}
        }
        return count;
    }

    private void returnToPatchOrReassign(SimulatedWorker w) {
        if (w.assignedPatch != null && !w.assignedPatch.isDepleted()) {
            w.targetPosition = miningTarget(w.assignedPatch, w.baseIndex);
            w.task           = WorkerTask.MINING_MINERALS;
            return;
        }
        assignToNearestPatch(w);
    }

    private void assignToNearestPatch(SimulatedWorker w) {
        if (w.baseIndex < 0 || w.baseIndex >= bases.size()) {
            w.task = WorkerTask.IDLE;
            return;
        }
        BaseEntry    base            = bases.get(w.baseIndex);
        MineralPatch nearest         = null;
        int          nearestAssigned = Integer.MAX_VALUE;
        double       nearestDist     = Double.MAX_VALUE;

        for (MineralPatch p : base.patches()) {
            if (p.isDepleted()) {continue;}
            int    assigned = countAssignedWorkers(p, w);
            double d        = w.position.distanceTo(p.position);
            if (assigned < nearestAssigned || (assigned == nearestAssigned && d < nearestDist)) {
                nearestAssigned = assigned;
                nearestDist     = d;
                nearest         = p;
            }
        }

        if (nearest == null) {
            w.task          = WorkerTask.IDLE;
            w.assignedPatch = null;
            return;
        }

        w.assignedPatch  = nearest;
        w.targetPosition = miningTarget(nearest, w.baseIndex);
        w.task           = WorkerTask.MINING_MINERALS;
        if (w.isAtTarget()) {
            if (nearest.miners.size() < MineralPatch.MAX_MINERS || nearest.miners.contains(w)) {
                if (!nearest.miners.contains(w)) {nearest.miners.add(w);}
                w.phaseEndLoop = currentTickStartLoop + nearest.approachDockingLoops + SimulatedWorker.MINING_DURATION_LOOPS;
            }
        }
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
            if (w.task == WorkerTask.MINING_MINERALS || w.task == WorkerTask.RETURNING_MINERALS
                || w.task == WorkerTask.DEPOSITING_MINERALS || w.task == WorkerTask.TRANSFERRING) {
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

    void syncTotalGasWorkers(int target) {
        int current = totalGasWorkers();
        if (current == target) {return;}
        if (current > target) {
            int excess = current - target;
            for (GasSlot slot : gasSlots) {
                while (excess > 0 && !slot.assignedWorkers.isEmpty()) {
                    SimulatedWorker w = slot.assignedWorkers.remove(slot.assignedWorkers.size() - 1);
                    slot.removeWorker(w);
                    w.assignedGasSlot = null;
                    w.task            = WorkerTask.IDLE;
                    assignToNearestPatch(w);
                    excess--;
                }
            }
        } else {
            int needed = target - current;
            for (GasSlot slot : gasSlots) {
                while (needed > 0 && slot.assignedWorkers.size() < GasSlot.MAX_WORKERS) {
                    SimulatedWorker best = null;
                    for (SimulatedWorker w : workers.values()) {
                        if (w.baseIndex != slot.baseIndex) {continue;}
                        if (w.assignedGasSlot != null) {continue;}
                        if (w.task != WorkerTask.MINING_MINERALS && w.task != WorkerTask.RETURNING_MINERALS && w.task != WorkerTask.DEPOSITING_MINERALS && w.task != WorkerTask.IDLE) {
                            continue;
                        }
                        best = w;
                        break;
                    }
                    if (best == null) {break;}
                    if (best.assignedPatch != null && best.assignedPatch.miners.contains(best)) {
                        best.assignedPatch.miners.remove(best);
                    }
                    best.assignedPatch   = null;
                    best.assignedGasSlot = slot;
                    best.task            = WorkerTask.HARVESTING_GAS;
                    best.targetPosition  = slot.position;
                    slot.assignedWorkers.add(best);
                    needed--;
                }
            }
        }
    }


    private int countMineralWorkersAtBase(int baseIdx) {
        int count = 0;
        for (SimulatedWorker w : workers.values()) {
            if (w.baseIndex == baseIdx
                && (w.task == WorkerTask.MINING_MINERALS || w.task == WorkerTask.RETURNING_MINERALS
                    || w.task == WorkerTask.DEPOSITING_MINERALS || w.task == WorkerTask.TRANSFERRING)) {
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


    private record BuildingWorkerReturn(String workerTag, long returnAtTick) {}

    private final List<BuildingWorkerReturn> pendingBuilderReturns = new ArrayList<>();

    void pauseWorkerForBuilding(Point2d buildSite, long returnAtTick) {
        SimulatedWorker nearest     = null;
        double          nearestDist = Double.MAX_VALUE;
        for (SimulatedWorker w : workers.values()) {
            if (w.task != WorkerTask.MINING_MINERALS && w.task != WorkerTask.RETURNING_MINERALS && w.task != WorkerTask.DEPOSITING_MINERALS) {continue;}
            double d = w.position.distanceTo(buildSite);
            if (d < nearestDist) {
                nearestDist = d;
                nearest     = w;
            }
        }
        if (nearest == null) {return;}
        if (nearest.assignedPatch != null && nearest.assignedPatch.miners.contains(nearest)) {
            nearest.assignedPatch.miners.remove(nearest);
        }
        nearest.assignedPatch = null;
        nearest.task          = WorkerTask.BUILDING;
        pendingBuilderReturns.add(new BuildingWorkerReturn(nearest.tag, returnAtTick));
    }

    void tickBuilderReturns(long currentTick) {
        var it = pendingBuilderReturns.iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (currentTick >= entry.returnAtTick()) {
                SimulatedWorker w = workers.get(entry.workerTag());
                if (w != null && w.task == WorkerTask.BUILDING) {
                    assignToNearestPatch(w);
                }
                it.remove();
            }
        }
    }

    void onWorkerScouting(String tag, double returnAtLoop) {
        SimulatedWorker w = workers.get(tag);
        if (w == null) {return;}
        if (w.assignedPatch != null && w.assignedPatch.miners.contains(w)) {
            w.assignedPatch.miners.remove(w);
        }
        w.assignedPatch = null;
        if (w.assignedGasSlot != null) {
            w.assignedGasSlot.removeWorker(w);
            w.assignedGasSlot = null;
        }
        w.task            = WorkerTask.SCOUTING;
        w.scoutReturnLoop = returnAtLoop;
    }

    void onWorkerReturnFromScout(String tag) {
        SimulatedWorker w = workers.get(tag);
        if (w == null || w.task != WorkerTask.SCOUTING) {return;}
        w.task = WorkerTask.IDLE;
        assignToNearestPatch(w);
    }

    private void tickTransferringWorker(SimulatedWorker w) {
        w.moveToward();
        if (w.isAtTarget()) {
            assignToNearestPatch(w);
        }
    }


    SimulatedWorker worker(String tag) {
        return workers.get(tag);
    }

    java.util.Collection<SimulatedWorker> allWorkers() {
        return workers.values();
    }

    void reset() {
        bases.clear();
        gasSlots.clear();
        workers.clear();
        pendingBuilderReturns.clear();
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

    List<io.quarkmind.sc2.playbook.WorkerSnapshot> workerSnapshots() {
        return workers.values().stream()
                      .map(WorkerSimulation::toWorkerSnapshot)
                      .toList();
    }

    void addWorkerForTest(SimulatedWorker w) {
        workers.put(w.tag, w);
    }


    private static io.quarkmind.sc2.playbook.WorkerSnapshot toWorkerSnapshot(SimulatedWorker w) {
        boolean carrying = w.cargo > 0;
        io.quarkmind.domain.WorkerAction action = switch (w.task) {
            case MINING_MINERALS -> io.quarkmind.domain.WorkerAction.GATHERING_MINERALS;
            case RETURNING_MINERALS, DEPOSITING_MINERALS -> io.quarkmind.domain.WorkerAction.RETURNING_MINERALS;
            case HARVESTING_GAS -> io.quarkmind.domain.WorkerAction.GATHERING_GAS;
            case RETURNING_GAS, DEPOSITING_GAS -> io.quarkmind.domain.WorkerAction.RETURNING_GAS;
            case IDLE -> io.quarkmind.domain.WorkerAction.IDLE;
            case BUILDING, SCOUTING, TRANSFERRING -> io.quarkmind.domain.WorkerAction.OTHER;
        };
        return new io.quarkmind.sc2.playbook.WorkerSnapshot(w.tag, w.position, action, carrying);
    }
}
