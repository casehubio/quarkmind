package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.observation.raw.ObservationRaw;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.GameResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Refs #763 — per-loop mineral worker physics profiler.
 * Records per-worker position and cargo at every game loop during a controlled
 * measurement window. Output feeds WorkerPhysicsAnalyser for phase decomposition.
 */
@Tag("sc2")
class WorkerPhysicsProfilerTest {

    private static final Set<Units> MINERAL_FIELDS = Set.of(
            Units.NEUTRAL_MINERAL_FIELD, Units.NEUTRAL_MINERAL_FIELD750,
            Units.NEUTRAL_RICH_MINERAL_FIELD, Units.NEUTRAL_RICH_MINERAL_FIELD750);

    private static final Set<Units> TOWN_HALLS = Set.of(
            Units.PROTOSS_NEXUS, Units.TERRAN_COMMAND_CENTER,
            Units.TERRAN_ORBITAL_COMMAND, Units.TERRAN_PLANETARY_FORTRESS,
            Units.ZERG_HATCHERY, Units.ZERG_LAIR, Units.ZERG_HIVE);

    record LoopMineralObs(int gameLoop, long tag, float x, float y, boolean carrying) {}

    @Test
    void profileMineralWorkers8w() throws Exception {
        recordScenario("playbooks/cal-mineral-baseline-8w.yaml", 8,
                "mineral-8w", 100, 50);
    }

    @Test
    void profileMineralWorkers16w() throws Exception {
        recordScenario("playbooks/cal-mineral-saturated-16w.yaml", 16,
                       "mineral-16w", 150, 50);
    }

    @Test
    void profileMineralWorkers12w() throws Exception {
        recordScenario("playbooks/cal-mineral-baseline-12w.yaml", 12,
                       "mineral-12w", 100, 50);
    }

    @Test
    void profileMineralWorkers24w() throws Exception {
        recordScenario("playbooks/cal-mineral-saturated-24w.yaml", 24,
                       "mineral-24w", 200, 30);
    }

    @Test
    void profileMineralWorkers36w() throws Exception {
        recordScenario("playbooks/cal-single-base-probes.yaml", 36,
                       "mineral-36w", 250, 20);
    }

    record ProbeLifecycle(long tag, int firstSeenLoop, float spawnX, float spawnY,
                          int firstCarryLoop, int firstDeliveryLoop,
                          List<float[]> positions) {}

    @Test
    void profileFirstDeliveryTiming() throws Exception {
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

        Map<Long, ProbeLifecycle> probes       = new LinkedHashMap<>();
        Map<Long, Boolean>        prevCarrying = new LinkedHashMap<>();
        Map<Long, float[]>        thPositions  = new LinkedHashMap<>();
        int[]                     loopRef      = {0};
        int[]                     gameLoopRef  = {0};
        Set<Long>                 initialTags  = new java.util.HashSet<>();

        PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(transport,
                                                            "playbooks/cal-single-base-probes.yaml");

        SC2FrameCallback profiler = new SC2FrameCallback() {
            @Override
            public void onGameStart(ResponseGameInfo gameInfo) {
                adapter.onGameStart(gameInfo);
            }

            @Override
            public void onStep(Observation obs) throws InterruptedException {
                adapter.onStep(obs);
                ObservationRaw raw      = obs.getRaw().orElseThrow();
                int            gameLoop = obs.getGameLoop();
                gameLoopRef[0] = gameLoop;

                for (var u : raw.getUnits()) {
                    if (!(u.getType() instanceof Units type)) {continue;}
                    if (u.getAlliance() != Alliance.SELF) {continue;}
                    if (TOWN_HALLS.contains(type)) {
                        thPositions.putIfAbsent(u.getTag().getValue(),
                                                new float[]{u.getPosition().getX(), u.getPosition().getY()});
                    }
                    if (type != Units.PROTOSS_PROBE) {continue;}

                    long tag = u.getTag().getValue();
                    boolean carrying = u.getBuffs().stream()
                                        .anyMatch(b -> b.toString().contains("CARRY"));
                    boolean wasCarrying = prevCarrying.getOrDefault(tag, false);
                    float   px          = u.getPosition().getX();
                    float   py          = u.getPosition().getY();

                    if (loopRef[0] == 0) {
                        initialTags.add(tag);
                    }

                    ProbeLifecycle pl = probes.get(tag);
                    if (pl == null) {
                        pl = new ProbeLifecycle(tag, gameLoop, px, py, -1, -1, new ArrayList<>());
                        probes.put(tag, pl);
                    }
                    pl.positions().add(new float[]{px, py, carrying ? 1 : 0});

                    if (carrying && !wasCarrying && pl.firstCarryLoop() < 0) {
                        probes.put(tag, new ProbeLifecycle(tag, pl.firstSeenLoop(), pl.spawnX(), pl.spawnY(),
                                                           gameLoop, pl.firstDeliveryLoop(), pl.positions()));
                    }
                    if (!carrying && wasCarrying && pl.firstDeliveryLoop() < 0) {
                        probes.put(tag, new ProbeLifecycle(tag, pl.firstSeenLoop(), pl.spawnX(), pl.spawnY(),
                                                           pl.firstCarryLoop(), gameLoop, pl.positions()));
                    }
                    prevCarrying.put(tag, carrying);
                }
                loopRef[0]++;
            }

            @Override
            public void onGameEnd(GameResult result) {
                adapter.onGameEnd(result);
            }
        };

        transport.runGameLoop(profiler);
        while (!transport.isRunning()) {Thread.sleep(50);}
        while (transport.isRunning()) {Thread.sleep(100);}

        System.out.println("=== SC2 First-Delivery Timing (TorchesAIE, single-base probes) ===");
        System.out.printf("%-8s | %-8s | %-12s | %-12s | %-10s | %-10s | %-12s%n",
                          "Tag", "Initial?", "SeenLoop", "1stCarry", "1stDeliver", "Loops", "Ticks");
        System.out.println("-".repeat(85));

        List<Integer> newProbeDeliveryLoops = new ArrayList<>();
        for (var entry : probes.entrySet()) {
            ProbeLifecycle pl        = entry.getValue();
            boolean        isInitial = initialTags.contains(entry.getKey());
            int deliveryLoops = pl.firstDeliveryLoop() >= 0
                                ? pl.firstDeliveryLoop() - pl.firstSeenLoop() : -1;
            double deliveryTicks = deliveryLoops >= 0 ? deliveryLoops / (double) SC2Data.LOOPS_PER_TICK : -1;
            if (!isInitial && deliveryLoops >= 0) {
                newProbeDeliveryLoops.add(deliveryLoops);
            }
            System.out.printf("%-8d | %-8s | %-12d | %-12s | %-10s | %-10s | %-12s%n",
                              pl.tag() % 10000, isInitial ? "yes" : "NEW",
                              pl.firstSeenLoop(),
                              pl.firstCarryLoop() >= 0 ? String.valueOf(pl.firstCarryLoop()) : "—",
                              pl.firstDeliveryLoop() >= 0 ? String.valueOf(pl.firstDeliveryLoop()) : "—",
                              deliveryLoops >= 0 ? String.valueOf(deliveryLoops) : "—",
                              deliveryTicks >= 0 ? String.format("%.1f", deliveryTicks) : "—");
        }

        if (!newProbeDeliveryLoops.isEmpty()) {
            double mean = newProbeDeliveryLoops.stream().mapToInt(i -> i).average().orElse(0);
            double min  = newProbeDeliveryLoops.stream().mapToInt(i -> i).min().orElse(0);
            double max  = newProbeDeliveryLoops.stream().mapToInt(i -> i).max().orElse(0);
            System.out.printf("%nNew probe first-delivery: mean=%.1f loops (%.1f ticks), min=%d, max=%d, N=%d%n",
                              mean, mean / SC2Data.LOOPS_PER_TICK, (int) min, (int) max, newProbeDeliveryLoops.size());
        }

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                             .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        var output = new LinkedHashMap<String, Object>();
        output.put("scenario", "first-delivery-timing");
        output.put("map", "TorchesAIE_v4");
        output.put("totalProbes", probes.size());
        output.put("initialProbes", initialTags.size());
        output.put("newProbes", probes.size() - initialTags.size());
        output.put("thPositions", thPositions);
        var probeData = new ArrayList<Map<String, Object>>();
        for (var pl : probes.values()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("tag", pl.tag());
            m.put("initial", initialTags.contains(pl.tag()));
            m.put("firstSeenLoop", pl.firstSeenLoop());
            m.put("spawnX", pl.spawnX());
            m.put("spawnY", pl.spawnY());
            m.put("firstCarryLoop", pl.firstCarryLoop());
            m.put("firstDeliveryLoop", pl.firstDeliveryLoop());
            probeData.add(m);
        }
        output.put("probes", probeData);
        java.nio.file.Path outPath = java.nio.file.Path.of(System.getProperty("user.dir"))
                                                       .resolve("src/test/resources/profiler/first-delivery-timing-run1.json");
        java.nio.file.Files.createDirectories(outPath.getParent());
        mapper.writeValue(outPath.toFile(), output);
        System.out.printf("[PROFILER] Wrote probe data to %s%n", outPath);
    }


    private void recordScenario(String playbook, int targetWorkers,
                                String scenarioName, int stabilisationTicks,
                                int measurementTicks) throws Exception {
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

        List<LoopMineralObs> loopData         = new ArrayList<>();
        Map<Long, float[]>   patchPositions   = new LinkedHashMap<>();
        Map<Long, float[]>   thPositions      = new LinkedHashMap<>();
        int[]                loopRef          = {0};
        int[]                measureStartLoop = {-1};
        int[]                workerCountRef   = {0};

        PlaybookSC2Adapter adapter = new PlaybookSC2Adapter(transport, playbook);

        SC2FrameCallback profiler = new SC2FrameCallback() {
            @Override
            public void onGameStart(ResponseGameInfo gameInfo) {
                adapter.onGameStart(gameInfo);
            }

            @Override
            public void onStep(Observation obs) throws InterruptedException {
                adapter.onStep(obs);
                ObservationRaw raw      = obs.getRaw().orElseThrow();
                var            allUnits = raw.getUnits();

                int workerCount = 0;
                for (var u : allUnits) {
                    if (!(u.getType() instanceof Units type)) {continue;}
                    if (u.getAlliance() != Alliance.SELF) {
                        if (MINERAL_FIELDS.contains(type)) {
                            patchPositions.putIfAbsent(u.getTag().getValue(),
                                                       new float[]{u.getPosition().getX(), u.getPosition().getY()});
                        }
                        continue;
                    }
                    if (TOWN_HALLS.contains(type)) {
                        thPositions.putIfAbsent(u.getTag().getValue(),
                                                new float[]{u.getPosition().getX(), u.getPosition().getY()});
                    }
                    if (type == Units.PROTOSS_PROBE) {workerCount++;}
                }
                workerCountRef[0] = workerCount;

                if (workerCount >= targetWorkers && measureStartLoop[0] < 0
                    && loopRef[0] > stabilisationTicks * SC2Data.LOOPS_PER_TICK) {
                    measureStartLoop[0] = loopRef[0];
                    System.out.printf("[PROFILER] Measurement window starts at loop %d (tick %d), %d workers%n",
                                      loopRef[0], loopRef[0] / SC2Data.LOOPS_PER_TICK, workerCount);
                }

                if (measureStartLoop[0] >= 0
                    && loopRef[0] < measureStartLoop[0] + measurementTicks * SC2Data.LOOPS_PER_TICK) {
                    for (var u : allUnits) {
                        if (u.getAlliance() != Alliance.SELF) {continue;}
                        if (!(u.getType() instanceof Units ut) || ut != Units.PROTOSS_PROBE) {continue;}
                        boolean carrying = u.getBuffs().stream()
                                            .anyMatch(b -> b.toString().contains("CARRY"));
                        loopData.add(new LoopMineralObs(loopRef[0], u.getTag().getValue(),
                                                        u.getPosition().getX(), u.getPosition().getY(), carrying));
                    }
                }

                loopRef[0]++;
            }

            @Override
            public void onGameEnd(GameResult result) {
                adapter.onGameEnd(result);
            }
        };

        transport.runGameLoop(profiler);
        while (!transport.isRunning()) {Thread.sleep(50);}
        while (transport.isRunning()) {Thread.sleep(100);}

        assertFalse(loopData.isEmpty(), "Should have recorded per-loop data");
        assertTrue(measureStartLoop[0] >= 0,
                   "Should have reached target worker count (" + targetWorkers + "), last seen: " + workerCountRef[0]);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                             .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        var output = Map.of(
                "scenario", scenarioName,
                "map", "TorchesAIE_v4",
                "targetWorkers", targetWorkers,
                "measurementStartLoop", measureStartLoop[0],
                "measurementTicks", measurementTicks,
                "totalObservations", loopData.size(),
                "observations", loopData,
                "patchPositions", patchPositions,
                "thPositions", thPositions);
        java.nio.file.Path projectRoot = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path outPath = projectRoot.resolve(
                "src/test/resources/profiler/" + scenarioName + "-run1.json");
        java.nio.file.Files.createDirectories(outPath.getParent());
        mapper.writeValue(outPath.toFile(), output);
        System.out.printf("[PROFILER] Wrote %d observations (%d workers x %d loops) to %s%n",
                          loopData.size(), targetWorkers,
                          measurementTicks * SC2Data.LOOPS_PER_TICK, outPath);
    }
}
