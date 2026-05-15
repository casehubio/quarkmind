package io.quarkmind.sc2.replay;

import java.util.List;

public record DivergenceReport(List<TickSnapshot> ticks, Summary summary) {

    public record TickSnapshot(
        int tick,
        int groundTruthUnits,     int emulatedUnits,
        int groundTruthBuildings, int emulatedBuildings,
        int groundTruthMinerals,  int emulatedMinerals,
        int groundTruthVespene,   int emulatedVespene) {

        public int unitDelta()     { return Math.abs(emulatedUnits     - groundTruthUnits); }
        public int buildingDelta() { return Math.abs(emulatedBuildings - groundTruthBuildings); }
        public int mineralDelta()  { return Math.abs(emulatedMinerals  - groundTruthMinerals); }
        public int vespeneDelta()  { return Math.abs(emulatedVespene   - groundTruthVespene); }
        public boolean hasUnitDivergence()     { return emulatedUnits     != groundTruthUnits; }
        public boolean hasBuildingDivergence() { return emulatedBuildings != groundTruthBuildings; }
    }

    public record Summary(
        int firstUnitDivergenceTick,
        int firstBuildingDivergenceTick,
        int maxMineralDelta,
        int maxVespeneDelta,
        boolean economicallyAccurate) {}

    public static DivergenceReport from(List<TickSnapshot> ticks) {
        int firstUnit     = -1;
        int firstBuilding = -1;
        int maxMineral    = 0;
        int maxVespene    = 0;

        for (TickSnapshot t : ticks) {
            if (firstUnit     == -1 && t.hasUnitDivergence())     firstUnit     = t.tick();
            if (firstBuilding == -1 && t.hasBuildingDivergence()) firstBuilding = t.tick();
            maxMineral = Math.max(maxMineral, t.mineralDelta());
            maxVespene = Math.max(maxVespene, t.vespeneDelta());
        }

        boolean accurate = (firstUnit == -1) && (firstBuilding == -1);
        return new DivergenceReport(ticks, new Summary(firstUnit, firstBuilding, maxMineral, maxVespene, accurate));
    }

    public String renderReport() {
        var sb = new StringBuilder();
        sb.append(String.format("=== Divergence Report ===%n"));
        sb.append(String.format("Ticks: %d  |  Economically accurate: %s%n",
            ticks.size(), summary.economicallyAccurate() ? "YES" : "NO"));
        sb.append(String.format("First unit divergence:     tick %s%n",
            summary.firstUnitDivergenceTick()     == -1 ? "none" : summary.firstUnitDivergenceTick()));
        sb.append(String.format("First building divergence: tick %s%n",
            summary.firstBuildingDivergenceTick() == -1 ? "none" : summary.firstBuildingDivergenceTick()));
        sb.append(String.format("Max mineral delta: %d%n", summary.maxMineralDelta()));
        sb.append(String.format("Max vespene delta: %d%n", summary.maxVespeneDelta()));
        sb.append(String.format("%n%-6s  %-11s  %-13s  %-19s  %-19s%n",
            "Tick", "Units GT/EM", "Bldgs GT/EM", "Minerals GT/EM", "Vespene GT/EM"));
        boolean anyDivergent = false;
        for (TickSnapshot t : ticks) {
            boolean diverges = t.hasUnitDivergence() || t.hasBuildingDivergence()
                || t.mineralDelta() > 100 || t.vespeneDelta() > 50;
            if (diverges) {
                anyDivergent = true;
                sb.append(String.format("%-6d  %5d/%-5d  %6d/%-6d  %8d/%-9d  %8d/%-8d%n",
                    t.tick(),
                    t.groundTruthUnits(),     t.emulatedUnits(),
                    t.groundTruthBuildings(), t.emulatedBuildings(),
                    t.groundTruthMinerals(),  t.emulatedMinerals(),
                    t.groundTruthVespene(),   t.emulatedVespene()));
            }
        }
        if (!anyDivergent) {
            sb.append("(no divergent ticks)").append(System.lineSeparator());
        }
        return sb.toString();
    }
}
