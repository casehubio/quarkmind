package io.quarkmind.plugin.scouting;

import java.util.ArrayList;
import java.util.List;

public class TemporalWindowAccumulator {

    static final int MAX_WINDOWS = 10;
    static final int TICKS_PER_WINDOW = 60;
    static final int FEATURES_PER_WINDOW = FeatureIndexMaps.FEATURES_PER_WINDOW;

    private static final int NT = FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER;
    private static final int NP = FeatureIndexMaps.N_FEATURES_PER_PLAYER;
    private static final int COMP_END = FeatureIndexMaps.SPATIAL_OFFSET;

    private final List<WindowSnapshot> tickSnapshots = new ArrayList<>();

    public void addSnapshot(WindowSnapshot snapshot) {
        tickSnapshots.add(snapshot);
    }

    public List<float[]> getWindowedFeatures() {
        List<float[]> result = new ArrayList<>(MAX_WINDOWS);
        float[] prevPlayerAvg = null;
        float[] prevOpponentAvg = null;

        for (int w = 0; w < MAX_WINDOWS; w++) {
            int startTick = w * TICKS_PER_WINDOW;
            int endTick = Math.min(startTick + TICKS_PER_WINDOW, tickSnapshots.size());
            if (startTick >= tickSnapshots.size()) {
                result.add(new float[FEATURES_PER_WINDOW]);
                continue;
            }
            float[] window = new float[FEATURES_PER_WINDOW];
            int count = endTick - startTick;
            boolean anyVision = false;

            float[] playerAvg = new float[NT];
            float[] opponentAvg = new float[NT];

            for (int t = startTick; t < endTick; t++) {
                var snap = tickSnapshots.get(t);
                float vis = snap.scoutingVisibility();
                float binaryVis = vis > 0 ? 1.0f : 0.0f;
                for (int f = 0; f < NT; f++) {
                    playerAvg[f] += snap.playerFeatures()[f];
                    if (f < COMP_END) {
                        opponentAvg[f] += snap.opponentFeatures()[f] * vis;
                    } else {
                        opponentAvg[f] += snap.opponentFeatures()[f] * binaryVis;
                    }
                }
                if (vis > 0) anyVision = true;
            }
            for (int f = 0; f < NT; f++) {
                playerAvg[f] /= count;
                opponentAvg[f] /= count;
            }

            System.arraycopy(playerAvg, 0, window, 0, NT);
            computeDeltas(window, FeatureIndexMaps.DELTA_OFFSET, playerAvg, prevPlayerAvg);
            System.arraycopy(opponentAvg, 0, window, NP, NT);
            computeDeltas(window, NP + FeatureIndexMaps.DELTA_OFFSET, opponentAvg, prevOpponentAvg);

            float gapX = playerAvg[FeatureIndexMaps.SPATIAL_OFFSET] - opponentAvg[FeatureIndexMaps.SPATIAL_OFFSET];
            float gapY = playerAvg[FeatureIndexMaps.SPATIAL_OFFSET + 1] - opponentAvg[FeatureIndexMaps.SPATIAL_OFFSET + 1];
            window[FeatureIndexMaps.ARMY_GAP_INDEX] = (float) Math.sqrt(gapX * gapX + gapY * gapY);
            window[FeatureIndexMaps.HAS_VISION_INDEX] = anyVision ? 1.0f : 0.0f;

            prevPlayerAvg = playerAvg;
            prevOpponentAvg = opponentAvg;
            result.add(window);
        }
        return result;
    }

    private static void computeDeltas(float[] window, int destOffset, float[] current, float[] previous) {
        if (previous == null) return;
        float curArmySupply = computeArmySupply(current);
        float prevArmySupply = computeArmySupply(previous);
        window[destOffset] = curArmySupply - prevArmySupply;
        float curWorkers = computeWorkerCount(current);
        float prevWorkers = computeWorkerCount(previous);
        window[destOffset + 1] = curWorkers - prevWorkers;
        float curProd = computeProductionBuildingCount(current);
        float prevProd = computeProductionBuildingCount(previous);
        window[destOffset + 2] = curProd - prevProd;
        float curTech = computeTechBuildingCount(current);
        float prevTech = computeTechBuildingCount(previous);
        window[destOffset + 3] = curTech - prevTech;
    }

    private static float computeArmySupply(float[] features) {
        float supply = 0;
        for (var entry : FeatureIndexMaps.UNIT_INDEX.entrySet()) {
            if (!io.quarkmind.domain.SC2Data.isWorker(entry.getKey())) {
                supply += features[FeatureIndexMaps.N_BUILDINGS + entry.getValue()]
                    * io.quarkmind.domain.SC2Data.supplyCost(entry.getKey());
            }
        }
        return supply;
    }

    private static float computeWorkerCount(float[] features) {
        float count = 0;
        for (var entry : FeatureIndexMaps.UNIT_INDEX.entrySet()) {
            if (io.quarkmind.domain.SC2Data.isWorker(entry.getKey())) {
                count += features[FeatureIndexMaps.N_BUILDINGS + entry.getValue()];
            }
        }
        return count;
    }

    private static float computeProductionBuildingCount(float[] features) {
        float count = 0;
        for (var entry : FeatureIndexMaps.BUILDING_INDEX.entrySet()) {
            if (io.quarkmind.domain.SC2Data.isProductionBuilding(entry.getKey())) {
                count += features[entry.getValue()];
            }
        }
        return count;
    }

    private static float computeTechBuildingCount(float[] features) {
        float count = 0;
        for (var entry : FeatureIndexMaps.BUILDING_INDEX.entrySet()) {
            if (io.quarkmind.domain.SC2Data.isTechBuilding(entry.getKey())) {
                count += features[entry.getValue()];
            }
        }
        return count;
    }

    public void reset() {
        tickSnapshots.clear();
    }
}
