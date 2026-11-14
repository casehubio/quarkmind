package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.observation.raw.ObservationRaw;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.GameResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Refs #665 — profiles gas worker cycle timing and building radii from real SC2.
 * Measures where gas workers enter/exit the geyser building and where they deposit,
 * to determine the effective walk distances for the EM gas model.
 */
@Tag("sc2")
class GasCycleProfilerTest {

    private static final Set<Units> GAS_BUILDINGS = Set.of(
            Units.PROTOSS_ASSIMILATOR, Units.TERRAN_REFINERY, Units.ZERG_EXTRACTOR);

    private static final Set<Units> TOWN_HALLS = Set.of(
            Units.PROTOSS_NEXUS, Units.TERRAN_COMMAND_CENTER,
            Units.TERRAN_ORBITAL_COMMAND, Units.TERRAN_PLANETARY_FORTRESS,
            Units.ZERG_HATCHERY, Units.ZERG_LAIR, Units.ZERG_HIVE);

    private static final Set<Units> WORKERS = Set.of(
            Units.PROTOSS_PROBE, Units.TERRAN_SCV, Units.ZERG_DRONE);

    private static final Set<Units> GEYSERS = Set.of(
            Units.NEUTRAL_VESPENE_GEYSER, Units.NEUTRAL_RICH_VESPENE_GEYSER,
            Units.NEUTRAL_PROTOSS_VESPENE_GEYSER, Units.NEUTRAL_PURIFIER_VESPENE_GEYSER,
            Units.NEUTRAL_SHAKURAS_VESPENE_GEYSER, Units.NEUTRAL_SPACE_PLATFORM_GEYSER);

    record UnitPos(long tag, float x, float y, float radius, Units type) {}
    record WorkerPos(long tag, float x, float y, boolean carrying, String action, long targetTag) {}
    record TickData(int tick, int gameLoop, List<UnitPos> buildings, List<WorkerPos> gasWorkers) {}

    @Test
    void profileGasCycle() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                "StarCraft II not installed — skipping live SC2 test");

        QuarkusSC2Transport transport = new QuarkusSC2Transport();
        transport.sc2Port = 8168;
        transport.mapName = "TorchesAIE_v4";
        transport.difficultyStr = "VERY_EASY";
        transport.aiRaceStr = "RANDOM";
        transport.botRaceStr = "PROTOSS";
        transport.connectRetryCount = 60;
        transport.connectRetryIntervalMs = 5000;
        transport.connect();
        transport.createGame();
        transport.joinGame();

        List<TickData> ticks = new ArrayList<>();
        int[] gameLoopRef = {0};
        int[] tickRef = {-1};
        boolean[] gasBuildingDone = {false};

        PlaybookSC2Adapter playbook = new PlaybookSC2Adapter(
                transport, "playbooks/cal-gas-baseline-3w.yaml");

        SC2FrameCallback profiler = new SC2FrameCallback() {
            @Override
            public void onGameStart(ResponseGameInfo gameInfo) {
                playbook.onGameStart(gameInfo);
            }

            @Override
            public void onStep(Observation obs) throws InterruptedException {
                playbook.onStep(obs);

                int emTick = gameLoopRef[0] / SC2Data.LOOPS_PER_TICK;
                if (emTick > tickRef[0]) {
                    tickRef[0] = emTick;

                    ObservationRaw raw = obs.getRaw().orElseThrow();
                    var allUnits = raw.getUnits();

                    List<UnitPos> buildings = new ArrayList<>();
                    List<WorkerPos> gasWorkers = new ArrayList<>();

                    for (var u : allUnits) {
                        if (u.getAlliance() != Alliance.SELF) {
                            if (GEYSERS.contains(u.getType())) {
                                buildings.add(new UnitPos(
                                        u.getTag().getValue(), u.getPosition().getX(),
                                        u.getPosition().getY(), u.getRadius(),
                                        (Units) u.getType()));
                            }
                            continue;
                        }
                        Units type = (Units) u.getType();
                        if (GAS_BUILDINGS.contains(type) || TOWN_HALLS.contains(type)) {
                            buildings.add(new UnitPos(
                                    u.getTag().getValue(), u.getPosition().getX(),
                                    u.getPosition().getY(), u.getRadius(), type));
                            if (GAS_BUILDINGS.contains(type) && u.getBuildProgress() >= 1.0f) {
                                gasBuildingDone[0] = true;
                            }
                        }
                        if (WORKERS.contains(type) && gasBuildingDone[0]) {
                            String action = classifyAction(u);
                            if ("GATHERING_GAS".equals(action) || "RETURNING_GAS".equals(action)
                                    || "IDLE_GAS".equals(action)) {
                                long targetTag = u.getOrders().stream().findFirst()
                                        .map(o -> o.getTargetedUnitTag().map(
                                                t -> t.getValue()).orElse(0L))
                                        .orElse(0L);
                                gasWorkers.add(new WorkerPos(
                                        u.getTag().getValue(), u.getPosition().getX(),
                                        u.getPosition().getY(),
                                        u.getBuffs().stream().anyMatch(
                                                b -> b.toString().contains("CARRY")),
                                        action, targetTag));
                            }
                        }
                    }

                    ticks.add(new TickData(emTick, gameLoopRef[0], buildings, gasWorkers));
                }
                gameLoopRef[0]++;
            }

            @Override
            public void onGameEnd(GameResult result) {
                playbook.onGameEnd(result);
            }
        };

        transport.runGameLoop(profiler);
        while (!transport.isRunning()) { Thread.sleep(50); }
        while (transport.isRunning()) { Thread.sleep(100); }

        assertFalse(ticks.isEmpty(), "Should have recorded tick data");

        printBuildingRadii(ticks);
        printGasWorkerCycleAnalysis(ticks);
        printWalkDistanceAnalysis(ticks);
    }

    record LoopWorkerObs(int gameLoop, long tag, float x, float y, boolean carrying, String action) {}

    @Test
    void profileGasCycleDecomposition() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available(),
                   "StarCraft II not installed — skipping live SC2 test");

        QuarkusSC2Transport transport = new QuarkusSC2Transport();
        transport.sc2Port                = 8168;
        transport.mapName                = "TorchesAIE_v4";
        transport.difficultyStr          = "VERY_EASY";
        transport.aiRaceStr              = "RANDOM";
        transport.botRaceStr             = "PROTOSS";
        transport.connectRetryCount      = 60;
        transport.connectRetryIntervalMs = 5000;
        transport.connect();
        transport.createGame();
        transport.joinGame();

        List<LoopWorkerObs> loopData           = new ArrayList<>();
        Map<Long, float[]>  buildingPositions  = new LinkedHashMap<>();
        Map<Long, Float>    buildingRadii      = new LinkedHashMap<>();
        Map<Long, Units>    buildingTypes      = new LinkedHashMap<>();
        Set<Long>           knownGasWorkerTags = new LinkedHashSet<>();
        int[]               loopRef            = {0};

        PlaybookSC2Adapter playbook = new PlaybookSC2Adapter(
                transport, "playbooks/cal-gas-baseline-3w.yaml");

        SC2FrameCallback profiler = new SC2FrameCallback() {
            @Override
            public void onGameStart(ResponseGameInfo gameInfo) {
                playbook.onGameStart(gameInfo);
            }

            @Override
            public void onStep(Observation obs) throws InterruptedException {
                playbook.onStep(obs);

                ObservationRaw raw      = obs.getRaw().orElseThrow();
                var            allUnits = raw.getUnits();

                Set<Long> visibleThisLoop = new HashSet<>();

                for (var u : allUnits) {
                    if (u.getAlliance() != Alliance.SELF) {continue;}
                    Units type = (Units) u.getType();
                    long  tag  = u.getTag().getValue();

                    if (GAS_BUILDINGS.contains(type) && u.getBuildProgress() >= 1.0f) {
                        buildingPositions.putIfAbsent(tag, new float[]{u.getPosition().getX(), u.getPosition().getY()});
                        buildingRadii.putIfAbsent(tag, u.getRadius());
                        buildingTypes.putIfAbsent(tag, type);
                    }
                    if (TOWN_HALLS.contains(type)) {
                        buildingPositions.putIfAbsent(tag, new float[]{u.getPosition().getX(), u.getPosition().getY()});
                        buildingRadii.putIfAbsent(tag, u.getRadius());
                        buildingTypes.putIfAbsent(tag, type);
                    }

                    if (WORKERS.contains(type)) {
                        String action = classifyAction(u);
                        if ("GATHERING_GAS".equals(action) || "RETURNING_GAS".equals(action)) {
                            knownGasWorkerTags.add(tag);
                            visibleThisLoop.add(tag);
                            loopData.add(new LoopWorkerObs(loopRef[0], tag,
                                                           u.getPosition().getX(), u.getPosition().getY(),
                                                           u.getBuffs().stream().anyMatch(b -> b.toString().contains("CARRY")),
                                                           action));
                        } else if (knownGasWorkerTags.contains(tag)) {
                            visibleThisLoop.add(tag);
                            loopData.add(new LoopWorkerObs(loopRef[0], tag,
                                                           u.getPosition().getX(), u.getPosition().getY(),
                                                           u.getBuffs().stream().anyMatch(b -> b.toString().contains("CARRY")),
                                                           action));
                        }
                    }
                }

                for (long knownTag : knownGasWorkerTags) {
                    if (!visibleThisLoop.contains(knownTag)) {
                        loopData.add(new LoopWorkerObs(loopRef[0], knownTag,
                                                       Float.NaN, Float.NaN, false, "INSIDE_BUILDING"));
                    }
                }

                loopRef[0]++;
            }

            @Override
            public void onGameEnd(GameResult result) {
                playbook.onGameEnd(result);
            }
        };

        transport.runGameLoop(profiler);
        while (!transport.isRunning()) {Thread.sleep(50);}
        while (transport.isRunning()) {Thread.sleep(100);}

        assertFalse(loopData.isEmpty(), "Should have recorded loop data");

        float gasX = 0, gasY = 0, thX = 0, thY = 0;
        float gasR = 0, thR = 0;
        for (var entry : buildingPositions.entrySet()) {
            Units   type = buildingTypes.get(entry.getKey());
            float[] pos  = entry.getValue();
            float   rad  = buildingRadii.get(entry.getKey());
            if (GAS_BUILDINGS.contains(type)) {
                gasX = pos[0];
                gasY = pos[1];
                gasR = rad;
            }
            if (TOWN_HALLS.contains(type)) {
                thX = pos[0];
                thY = pos[1];
                thR = rad;
            }
        }

        System.out.println("=== GAS CYCLE DECOMPOSITION (per-game-loop) ===");
        System.out.printf("Gas building: (%.1f, %.1f) r=%.2f  TH: (%.1f, %.1f) r=%.2f%n",
                          gasX, gasY, gasR, thX, thY, thR);
        double ctc = Math.sqrt(Math.pow(gasX - thX, 2) + Math.pow(gasY - thY, 2));
        System.out.printf("Center-to-center: %.2f  Edge-to-edge: %.2f%n%n", ctc, ctc - gasR - thR);

        for (long workerTag : knownGasWorkerTags) {
            List<LoopWorkerObs> workerLoops = loopData.stream()
                                                      .filter(d -> d.tag == workerTag).toList();
            if (workerLoops.size() < 50) {continue;}

            System.out.printf("--- Worker %d (%d loops observed) ---%n", workerTag % 10000, workerLoops.size());

            String       prevAction   = "";
            int          phaseStart   = 0;
            float        entryDistGas = 0, exitDistGas = 0;
            List<String> phases       = new ArrayList<>();

            for (int i = 0; i < workerLoops.size(); i++) {
                LoopWorkerObs obs       = workerLoops.get(i);
                String        curAction = obs.action;
                if (!curAction.equals(prevAction)) {
                    if (!prevAction.isEmpty()) {
                        int    duration = obs.gameLoop - phaseStart;
                        String distInfo = "";
                        if ("INSIDE_BUILDING".equals(prevAction)) {
                            distInfo = String.format(" (entered at d=%.2f from gas)", entryDistGas);
                        }
                        if ("INSIDE_BUILDING".equals(curAction) && !Float.isNaN(obs.x)) {
                            entryDistGas = (float) Math.sqrt(Math.pow(obs.x - gasX, 2) + Math.pow(obs.y - gasY, 2));
                        }
                        if ("INSIDE_BUILDING".equals(prevAction) && !Float.isNaN(obs.x)) {
                            exitDistGas = (float) Math.sqrt(Math.pow(obs.x - gasX, 2) + Math.pow(obs.y - gasY, 2));
                            distInfo += String.format(" (exited at d=%.2f)", exitDistGas);
                        }
                        phases.add(String.format("  %-20s loop %5d-%5d (%3d loops = %.1f ticks)%s",
                                                 prevAction, phaseStart, obs.gameLoop - 1, duration,
                                                 duration / 22.0, distInfo));
                    }
                    prevAction = curAction;
                    phaseStart = obs.gameLoop;
                    if ("INSIDE_BUILDING".equals(curAction) && i > 0) {
                        LoopWorkerObs prev = workerLoops.get(i - 1);
                        if (!Float.isNaN(prev.x)) {
                            entryDistGas = (float) Math.sqrt(Math.pow(prev.x - gasX, 2) + Math.pow(prev.y - gasY, 2));
                        }
                    }
                }
            }
            if (!prevAction.isEmpty()) {
                LoopWorkerObs last     = workerLoops.get(workerLoops.size() - 1);
                int           duration = last.gameLoop - phaseStart + 1;
                phases.add(String.format("  %-20s loop %5d-%5d (%3d loops = %.1f ticks)",
                                         prevAction, phaseStart, last.gameLoop, duration, duration / 22.0));
            }

            phases.stream().limit(40).forEach(System.out::println);
            if (phases.size() > 40) {System.out.printf("  ... and %d more phases%n", phases.size() - 40);}

            long insideCount = phases.stream().filter(p -> p.contains("INSIDE_BUILDING")).count();
            var insideDurations = phases.stream()
                                        .filter(p -> p.contains("INSIDE_BUILDING"))
                                        .map(p -> {
                                            int parenIdx = p.indexOf('(');
                                            int loopIdx  = p.indexOf("loops", parenIdx);
                                            return Integer.parseInt(p.substring(parenIdx + 1, loopIdx).trim());
                                        }).toList();
            if (!insideDurations.isEmpty()) {
                double avgInside = insideDurations.stream().mapToInt(i -> i).average().orElse(0);
                int    minInside = insideDurations.stream().mapToInt(i -> i).min().orElse(0);
                int    maxInside = insideDurations.stream().mapToInt(i -> i).max().orElse(0);
                System.out.printf("  INSIDE_BUILDING: %d cycles, avg=%.0f loops (%.1f ticks), range=%d-%d%n%n",
                                  insideCount, avgInside, avgInside / 22.0, minInside, maxInside);
            }
        }
    }


    private static String classifyAction(com.github.ocraft.s2client.protocol.unit.Unit u) {
        var orders = u.getOrders();
        if (orders.isEmpty()) return "IDLE";
        UnitOrder order = orders.iterator().next();
        var ability = order.getAbility();
        if (ability == Abilities.HARVEST_GATHER) return "GATHERING_GAS";
        if (ability == Abilities.HARVEST_RETURN) return "RETURNING_GAS";
        String abilStr = ability.toString();
        if (abilStr.contains("HARVEST_GATHER")) return "GATHERING_GAS";
        if (abilStr.contains("HARVEST_RETURN")) return "RETURNING_GAS";
        return "OTHER:" + abilStr;
    }

    private void printBuildingRadii(List<TickData> ticks) {
        System.out.println("=== BUILDING RADII (from SC2 API) ===");
        Map<Units, Float> radii = new LinkedHashMap<>();
        Map<Units, float[]> positions = new LinkedHashMap<>();

        for (TickData td : ticks) {
            for (UnitPos bp : td.buildings) {
                radii.putIfAbsent(bp.type, bp.radius);
                positions.putIfAbsent(bp.type, new float[]{bp.x, bp.y});
            }
        }

        System.out.printf("%-30s %8s %12s%n", "Building Type", "Radius", "Position");
        for (var entry : radii.entrySet()) {
            float[] pos = positions.get(entry.getKey());
            System.out.printf("%-30s %8.2f (%7.2f, %7.2f)%n",
                    entry.getKey(), entry.getValue(), pos[0], pos[1]);
        }

        Float gasRadius = null;
        Float townHallRadius = null;
        Float geyserRadius = null;
        float[] gasPos = null;
        float[] thPos = null;

        for (var entry : radii.entrySet()) {
            if (GAS_BUILDINGS.contains(entry.getKey())) {
                gasRadius = entry.getValue();
                gasPos = positions.get(entry.getKey());
            }
            if (TOWN_HALLS.contains(entry.getKey())) {
                townHallRadius = entry.getValue();
                thPos = positions.get(entry.getKey());
            }
            if (GEYSERS.contains(entry.getKey())) {
                geyserRadius = entry.getValue();
            }
        }

        if (gasPos != null && thPos != null) {
            double centerDist = Math.sqrt(
                    Math.pow(gasPos[0] - thPos[0], 2) + Math.pow(gasPos[1] - thPos[1], 2));
            System.out.printf("%nCenter-to-center distance (gas→TH): %.2f%n", centerDist);
            if (gasRadius != null && townHallRadius != null) {
                System.out.printf("Edge-to-edge distance: %.2f (gas radius=%.2f, TH radius=%.2f)%n",
                        centerDist - gasRadius - townHallRadius, gasRadius, townHallRadius);
            }
            if (geyserRadius != null) {
                System.out.printf("Neutral geyser radius: %.2f%n", geyserRadius);
            }
        }
        System.out.println();
    }

    private void printGasWorkerCycleAnalysis(List<TickData> ticks) {
        System.out.println("=== GAS WORKER CYCLE ANALYSIS ===");

        float gasX = 0, gasY = 0, thX = 0, thY = 0;
        boolean found = false;
        for (TickData td : ticks) {
            for (UnitPos bp : td.buildings) {
                if (GAS_BUILDINGS.contains(bp.type)) { gasX = bp.x; gasY = bp.y; }
                if (TOWN_HALLS.contains(bp.type)) { thX = bp.x; thY = bp.y; found = true; }
            }
            if (found) break;
        }

        Map<Long, List<int[]>> workerPhases = new LinkedHashMap<>();

        for (TickData td : ticks) {
            for (WorkerPos wp : td.gasWorkers) {
                workerPhases.computeIfAbsent(wp.tag, k -> new ArrayList<>());
                double distToGas = Math.sqrt(Math.pow(wp.x - gasX, 2) + Math.pow(wp.y - gasY, 2));
                double distToTH = Math.sqrt(Math.pow(wp.x - thX, 2) + Math.pow(wp.y - thY, 2));
                int phase;
                if ("RETURNING_GAS".equals(wp.action)) {
                    phase = 2; // returning to base
                } else if (distToGas < 1.0) {
                    phase = 1; // inside/at geyser
                } else if (distToTH < 3.0 && !wp.carrying) {
                    phase = 3; // depositing
                } else {
                    phase = 0; // walking to geyser
                }
                workerPhases.get(wp.tag).add(new int[]{td.tick, phase, (int)(distToGas * 100), (int)(distToTH * 100)});
            }
        }

        System.out.printf("Gas workers tracked: %d%n", workerPhases.size());
        System.out.printf("Gas building at: (%.2f, %.2f)  TH at: (%.2f, %.2f)%n%n", gasX, gasY, thX, thY);

        for (var entry : workerPhases.entrySet()) {
            System.out.printf("--- Worker %d --- (%d ticks observed)%n", entry.getKey(), entry.getValue().size());

            List<int[]> phases = entry.getValue();
            int prevPhase = -1;
            int phaseStartTick = 0;
            List<String> transitions = new ArrayList<>();
            String[] phaseNames = {"WALK_TO_GAS", "INSIDE_GAS", "RETURNING", "DEPOSITING"};

            for (int[] p : phases) {
                if (p[1] != prevPhase) {
                    if (prevPhase >= 0) {
                        transitions.add(String.format("  %s: tick %d-%d (%d ticks)",
                                phaseNames[prevPhase], phaseStartTick, p[0] - 1, p[0] - phaseStartTick));
                    }
                    prevPhase = p[1];
                    phaseStartTick = p[0];
                }
            }
            if (prevPhase >= 0 && !phases.isEmpty()) {
                int lastTick = phases.get(phases.size() - 1)[0];
                transitions.add(String.format("  %s: tick %d-%d (%d ticks)",
                        phaseNames[prevPhase], phaseStartTick, lastTick, lastTick - phaseStartTick + 1));
            }

            transitions.stream().limit(30).forEach(System.out::println);
            if (transitions.size() > 30) System.out.printf("  ... and %d more transitions%n", transitions.size() - 30);
            System.out.println();
        }
    }

    private void printWalkDistanceAnalysis(List<TickData> ticks) {
        System.out.println("=== WALK DISTANCE ANALYSIS — Worker position at phase transitions ===");

        float gasX = 0, gasY = 0, thX = 0, thY = 0;
        float gasR = 0, thR = 0;
        for (TickData td : ticks) {
            for (UnitPos bp : td.buildings) {
                if (GAS_BUILDINGS.contains(bp.type)) { gasX = bp.x; gasY = bp.y; gasR = bp.radius; }
                if (TOWN_HALLS.contains(bp.type)) { thX = bp.x; thY = bp.y; thR = bp.radius; }
            }
        }

        System.out.printf("%-8s %-6s %-6s %-12s %-10s %-10s %-8s%n",
                "Tick", "Worker", "Phase", "Action", "DistToGas", "DistToTH", "Carry");

        int printCount = 0;
        for (TickData td : ticks) {
            if (td.tick < 60 || td.tick > 200) continue;
            for (WorkerPos wp : td.gasWorkers) {
                double distToGas = Math.sqrt(Math.pow(wp.x - gasX, 2) + Math.pow(wp.y - gasY, 2));
                double distToTH = Math.sqrt(Math.pow(wp.x - thX, 2) + Math.pow(wp.y - thY, 2));

                if (distToGas < gasR + 1.0 || distToTH < thR + 1.0) {
                    System.out.printf("%-8d %-6d %-6s %-12s %-10.2f %-10.2f %-8s%n",
                            td.tick, (int)(wp.tag % 10000), wp.carrying ? "CARRY" : "EMPTY",
                            wp.action, distToGas, distToTH, wp.carrying);
                    printCount++;
                }
            }
            if (printCount > 200) {
                System.out.println("... (truncated at 200 transition points)");
                break;
            }
        }

        System.out.printf("%nBuilding radii — Gas: %.2f  TH: %.2f%n", gasR, thR);
        System.out.printf("Center-to-center: %.2f%n",
                Math.sqrt(Math.pow(gasX - thX, 2) + Math.pow(gasY - thY, 2)));
        System.out.printf("Edge-to-edge: %.2f%n",
                Math.sqrt(Math.pow(gasX - thX, 2) + Math.pow(gasY - thY, 2)) - gasR - thR);
    }
}
