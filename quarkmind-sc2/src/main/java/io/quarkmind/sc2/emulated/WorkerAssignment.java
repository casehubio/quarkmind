package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

import java.util.*;

class WorkerAssignment {

    private record BaseEntry(String baseTag, Point2d position,
                             Set<String> mineralWorkerTags,
                             Map<String, Set<String>> gasWorkerTagsByBuilding) {
        BaseEntry(String baseTag, Point2d position) {
            this(baseTag, position, new LinkedHashSet<>(), new LinkedHashMap<>());
        }
    }

    private final List<BaseEntry> bases = new ArrayList<>();
    private final Map<String, String> workerToBase = new HashMap<>();
    private final Set<String> unassignedWorkers = new LinkedHashSet<>();

    void onTownHallCompleted(String baseTag, Point2d position) {
        if (bases.stream().anyMatch(b -> b.baseTag.equals(baseTag))) return;
        BaseEntry newBase = new BaseEntry(baseTag, position);
        bases.add(newBase);
        redistributeOnExpansion(newBase);
    }

    void onInitialWorkers(List<String> workerTags, Point2d basePosition, String baseTag) {
        BaseEntry base = findBaseByTag(baseTag);
        if (base == null) return;
        for (String tag : workerTags) {
            base.mineralWorkerTags.add(tag);
            workerToBase.put(tag, baseTag);
        }
    }

    void onWorkerProduced(String workerTag, Point2d buildingPosition) {
        BaseEntry nearest = findNearestBase(buildingPosition);
        if (nearest == null) {
            unassignedWorkers.add(workerTag);
            return;
        }
        nearest.mineralWorkerTags.add(workerTag);
        workerToBase.put(workerTag, nearest.baseTag);
    }

    void onGasBuildingCompleted(String buildingTag, Point2d position) {
        BaseEntry nearest = findNearestBase(position);
        if (nearest == null) return;
        if (hasGasBuildingNear(position)) return;
        Set<String> gasWorkers = new LinkedHashSet<>();
        nearest.gasWorkerTagsByBuilding.put(buildingTag, gasWorkers);
        Iterator<String> it = nearest.mineralWorkerTags.iterator();
        int transferred = 0;
        while (it.hasNext() && transferred < 3) {
            String workerTag = it.next();
            it.remove();
            gasWorkers.add(workerTag);
            transferred++;
        }
    }

    private final List<Point2d> assignedGasPositions = new ArrayList<>();

    private boolean hasGasBuildingNear(Point2d position) {
        for (Point2d existing : assignedGasPositions) {
            double dx = position.x() - existing.x();
            double dy = position.y() - existing.y();
            if (dx * dx + dy * dy < 4.0) return true;
        }
        assignedGasPositions.add(position);
        return false;
    }

    void onWorkerDied(String workerTag) {
        String baseTag = workerToBase.remove(workerTag);
        if (baseTag == null) {
            unassignedWorkers.remove(workerTag);
            return;
        }
        BaseEntry base = findBaseByTag(baseTag);
        if (base == null) return;
        if (base.mineralWorkerTags.remove(workerTag)) return;
        for (Set<String> gasWorkers : base.gasWorkerTagsByBuilding.values()) {
            if (gasWorkers.remove(workerTag)) return;
        }
    }

    int[] mineralWorkersPerBase() {
        int[] result = new int[bases.size()];
        for (int i = 0; i < bases.size(); i++) {
            result[i] = bases.get(i).mineralWorkerTags.size();
        }
        return result;
    }

    int gasWorkersForBuilding(String buildingTag) {
        for (BaseEntry base : bases) {
            Set<String> workers = base.gasWorkerTagsByBuilding.get(buildingTag);
            if (workers != null) return workers.size();
        }
        return 0;
    }

    void reset() {
        bases.clear();
        workerToBase.clear();
        unassignedWorkers.clear();
        assignedGasPositions.clear();
    }

    private void redistributeOnExpansion(BaseEntry newBase) {
        final int optimalSaturation = 16;
        for (BaseEntry existing : bases) {
            if (existing == newBase) continue;
            int excess = existing.mineralWorkerTags.size() - optimalSaturation;
            if (excess <= 0) continue;
            int toTransfer = Math.min(excess, optimalSaturation - newBase.mineralWorkerTags.size());
            if (toTransfer <= 0) continue;
            Iterator<String> it = existing.mineralWorkerTags.iterator();
            int moved = 0;
            while (it.hasNext() && moved < toTransfer) {
                String tag = it.next();
                it.remove();
                newBase.mineralWorkerTags.add(tag);
                workerToBase.put(tag, newBase.baseTag);
                moved++;
            }
        }
        Iterator<String> unassigned = unassignedWorkers.iterator();
        while (unassigned.hasNext() && newBase.mineralWorkerTags.size() < optimalSaturation) {
            String tag = unassigned.next();
            unassigned.remove();
            newBase.mineralWorkerTags.add(tag);
            workerToBase.put(tag, newBase.baseTag);
        }
    }

    private BaseEntry findNearestBase(Point2d position) {
        BaseEntry nearest = null;
        double minDistSq = Double.MAX_VALUE;
        for (BaseEntry base : bases) {
            double dx = position.x() - base.position.x();
            double dy = position.y() - base.position.y();
            double dSq = dx * dx + dy * dy;
            if (dSq < minDistSq) {
                minDistSq = dSq;
                nearest = base;
            }
        }
        return nearest;
    }

    private BaseEntry findBaseByTag(String baseTag) {
        for (BaseEntry base : bases) {
            if (base.baseTag.equals(baseTag)) return base;
        }
        return null;
    }
}
