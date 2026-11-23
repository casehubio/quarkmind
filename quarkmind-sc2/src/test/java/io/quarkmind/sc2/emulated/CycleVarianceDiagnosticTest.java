package io.quarkmind.sc2.emulated;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.playbook.WorkerCadenceBaseline;
import io.quarkmind.sc2.playbook.WorkerCadenceSnapshot;
import io.quarkmind.sc2.playbook.WorkerSnapshot;
import io.quarkmind.sc2.replay.TrackerIntentExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-worker cycle variance diagnostic — compares EM cycle σ against SC2 baseline σ.
 * Decomposes variance by phase: walk-to-patch, wait, mine, walk-to-base, deposit.
 *
 * Refs #752
 */
@Tag("diagnostic")
class CycleVarianceDiagnosticTest {

    private static final float GAS_PER_WORKER_PER_MIN = 38.0f;
    private static final int TICKS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);

    @Test
    void controlledCycleVariance() {
        System.out.println("=== Controlled 12w Cycle Variance — EM vs SC2 ===\n");

        WorkerCadenceBaseline sc2Cadence = loadCadenceBaseline("calibration/cal-worker-cadence-12w.json");
        Map<String, List<Integer>> sc2DeliveryTicks = deriveSc2DeliveryTicks(sc2Cadence);
        Map<String, List<Integer>> sc2CycleLengths = deriveCycleLengths(sc2DeliveryTicks);

        System.out.println("--- SC2 per-worker cycle stats ---");
        System.out.printf("%-20s %6s %8s %8s %5s %5s%n", "worker", "cycles", "mean", "σ", "min", "max");
        DoubleSummaryStatistics sc2AllCycles = new DoubleSummaryStatistics();
        List<Double> sc2AllCycleValues = new ArrayList<>();
        for (var entry : sc2CycleLengths.entrySet()) {
            List<Integer> cycles = entry.getValue();
            if (cycles.isEmpty()) continue;
            double mean = cycles.stream().mapToInt(Integer::intValue).average().orElse(0);
            double sigma = stddev(cycles);
            int min = cycles.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = cycles.stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.printf("%-20s %6d %8.2f %8.2f %5d %5d%n",
                    entry.getKey(), cycles.size(), mean, sigma, min, max);
            cycles.forEach(c -> {
                sc2AllCycles.accept(c);
                sc2AllCycleValues.add((double) c);
            });
        }
        System.out.printf("%-20s %6d %8.2f %8.2f %5.0f %5.0f%n",
                "AGGREGATE", (int) sc2AllCycles.getCount(), sc2AllCycles.getAverage(),
                stddev(sc2AllCycleValues), sc2AllCycles.getMin(), sc2AllCycles.getMax());

        System.out.println("\n--- EM per-worker cycle stats ---");
        EmulatedGame em = new EmulatedGame();
        em.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));

        Map<String, List<Double>> emDeliveryLoops = new LinkedHashMap<>();
        Map<String, List<PhaseDuration>> emPhaseData = new LinkedHashMap<>();
        Map<String, Double> phaseStartLoop = new HashMap<>();

        em.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
            @Override
            public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {
                phaseStartLoop.put(tag + ":" + to.name(), loop);
                if (from != null) {
                    Double start = phaseStartLoop.remove(tag + ":" + from.name());
                    if (start != null) {
                        emPhaseData.computeIfAbsent(tag, k -> new ArrayList<>())
                                .add(new PhaseDuration(from, loop - start));
                    }
                }
            }

            @Override
            public void onDelivery(String tag, double minerals, double loop) {
                emDeliveryLoops.computeIfAbsent(tag, k -> new ArrayList<>()).add(loop);
            }
        });
        em.reset();

        for (int tick = 0; tick < 305; tick++) {
            em.tick();
        }

        System.out.printf("%-20s %6s %8s %8s %5s %5s%n", "worker", "cycles", "mean", "σ", "min", "max");
        DoubleSummaryStatistics emAllCycles = new DoubleSummaryStatistics();
        List<Double> emAllCycleValues = new ArrayList<>();
        for (var entry : emDeliveryLoops.entrySet()) {
            List<Double> deliveries = entry.getValue();
            if (deliveries.size() < 2) continue;
            List<Double> cycles = new ArrayList<>();
            for (int i = 1; i < deliveries.size(); i++) {
                cycles.add((deliveries.get(i) - deliveries.get(i - 1)) / SC2Data.LOOPS_PER_TICK);
            }
            double mean = cycles.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double sigma = stddev(cycles);
            double min = cycles.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = cycles.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            System.out.printf("%-20s %6d %8.2f %8.2f %5.1f %5.1f%n",
                    entry.getKey(), cycles.size(), mean, sigma, min, max);
            cycles.forEach(c -> {
                emAllCycles.accept(c);
                emAllCycleValues.add(c);
            });
        }
        System.out.printf("%-20s %6d %8.2f %8.2f %5.1f %5.1f%n",
                "AGGREGATE", (int) emAllCycles.getCount(), emAllCycles.getAverage(),
                stddev(emAllCycleValues), emAllCycles.getMin(), emAllCycles.getMax());

        System.out.println("\n--- Phase variance decomposition (EM) ---");
        Map<WorkerPhase, List<Double>> phaseAggregates = new EnumMap<>(WorkerPhase.class);
        for (var entry : emPhaseData.values()) {
            for (PhaseDuration pd : entry) {
                phaseAggregates.computeIfAbsent(pd.phase, k -> new ArrayList<>())
                        .add(pd.durationLoops / SC2Data.LOOPS_PER_TICK);
            }
        }
        System.out.printf("%-25s %6s %8s %8s %8s %8s%n", "phase", "count", "mean", "σ", "min", "max");
        for (WorkerPhase phase : WorkerPhase.values()) {
            List<Double> durations = phaseAggregates.getOrDefault(phase, List.of());
            if (durations.isEmpty()) continue;
            DoubleSummaryStatistics stats = durations.stream()
                    .mapToDouble(Double::doubleValue).summaryStatistics();
            System.out.printf("%-25s %6d %8.2f %8.2f %8.2f %8.2f%n",
                    phase, durations.size(), stats.getAverage(), stddev(durations),
                    stats.getMin(), stats.getMax());
        }

        System.out.printf("%n=== σ gap: SC2=%.2f  EM=%.2f  delta=%.2f ===%n",
                stddev(sc2AllCycleValues), stddev(emAllCycleValues),
                stddev(sc2AllCycleValues) - stddev(emAllCycleValues));
    }

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void oracleReplayCycleVariance() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                    .sorted().toList();
        }

        System.out.println("=== Oracle Replay Cycle Variance (Standalone EM) ===\n");

        Map<Race, List<Double>> cyclesByRace = new EnumMap<>(Race.class);
        Map<Race, Map<WorkerPhase, List<Double>>> phaseByRace = new EnumMap<>(Race.class);
        Map<Race, int[]> replayCounts = new EnumMap<>(Race.class);
        for (Race r : Race.values()) {
            cyclesByRace.put(r, new ArrayList<>());
            phaseByRace.put(r, new EnumMap<>(WorkerPhase.class));
            replayCounts.put(r, new int[]{0});
        }

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) {
                continue;
            }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    ReplaySimulatedGame gt = new ReplaySimulatedGame(replayPath, playerId);

                    EmulatedGame em = new EmulatedGame();
                    em.setPlayerRaceModel(RaceModelFactory.forRace(race));

                    List<Double> deliveryLoops = new ArrayList<>();
                    Map<String, Double> lastDelivery = new HashMap<>();
                    Map<String, Double> phaseStart = new HashMap<>();
                    Map<WorkerPhase, List<Double>> localPhases = phaseByRace.get(race);

                    em.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
                        @Override
                        public void onPhaseChange(String tag, WorkerPhase from, WorkerPhase to, double loop) {
                            phaseStart.put(tag + ":" + to.name(), loop);
                            if (from != null) {
                                Double start = phaseStart.remove(tag + ":" + from.name());
                                if (start != null) {
                                    double dur = (loop - start) / SC2Data.LOOPS_PER_TICK;
                                    localPhases.computeIfAbsent(from, k -> new ArrayList<>()).add(dur);
                                }
                            }
                        }

                        @Override
                        public void onDelivery(String tag, double minerals, double loop) {
                            Double prev = lastDelivery.put(tag, loop);
                            if (prev != null) {
                                deliveryLoops.add((loop - prev) / SC2Data.LOOPS_PER_TICK);
                            }
                        }
                    });

                    gt.reset();
                    em.setMineralPatchResources(gt.getMineralPatches());
                    em.resetForHarness();

                    int tickLimit = 5 * TICKS_PER_MINUTE;
                    int cursor = 0;
                    for (int tick = 0; tick < tickLimit && !gt.isComplete(); tick++) {
                        long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;
                        em.tick();
                        gt.tick();
                        GameState gtSnap = gt.snapshot();
                        em.processDeferredTrainIntents();
                        em.processDeferredResearchIntents();
                        while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                            em.applyIntent(intents.get(cursor));
                            cursor++;
                        }
                        int gtGasRate = gtSnap.playerEconomy().vespeneCollectionRate();
                        if (gtGasRate > 0) {
                            em.syncTotalGasWorkers(Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN)));
                        }
                    }

                    cyclesByRace.get(race).addAll(deliveryLoops);
                    replayCounts.get(race)[0]++;
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.printf("%-10s %8s %8s %8s %8s %8s%n", "race", "runs", "cycles", "mean", "σ", "range");
        for (Race race : Race.values()) {
            List<Double> cycles = cyclesByRace.get(race);
            int runs = replayCounts.get(race)[0];
            if (cycles.isEmpty()) continue;
            DoubleSummaryStatistics stats = cycles.stream().mapToDouble(Double::doubleValue).summaryStatistics();
            System.out.printf("%-10s %8d %8d %8.2f %8.2f %5.1f-%5.1f%n",
                    race, runs, cycles.size(), stats.getAverage(), stddev(cycles),
                    stats.getMin(), stats.getMax());
        }

        System.out.println("\n--- Per-phase variance by race ---");
        for (Race race : Race.values()) {
            Map<WorkerPhase, List<Double>> phases = phaseByRace.get(race);
            if (phases.isEmpty()) continue;
            System.out.printf("\n%s:%n", race);
            System.out.printf("  %-25s %8s %8s %8s%n", "phase", "mean", "σ", "count");
            for (WorkerPhase phase : WorkerPhase.values()) {
                List<Double> durations = phases.getOrDefault(phase, List.of());
                if (durations.isEmpty()) continue;
                DoubleSummaryStatistics stats = durations.stream()
                        .mapToDouble(Double::doubleValue).summaryStatistics();
                System.out.printf("  %-25s %8.2f %8.2f %8d%n",
                        phase, stats.getAverage(), stddev(durations), durations.size());
            }
        }
    }

    private record PhaseDuration(WorkerPhase phase, double durationLoops) {}

    private static Map<String, List<Integer>> deriveSc2DeliveryTicks(WorkerCadenceBaseline baseline) {
        Map<String, List<Integer>> deliveryTicks = new LinkedHashMap<>();
        Map<String, Boolean> prevCarrying = new HashMap<>();

        for (WorkerCadenceSnapshot snap : baseline.cadence()) {
            for (WorkerSnapshot ws : snap.workers()) {
                if (ws.mule()) continue;
                boolean wasCarrying = prevCarrying.getOrDefault(ws.tag(), false);
                if (wasCarrying && !ws.carrying()) {
                    deliveryTicks.computeIfAbsent(ws.tag(), k -> new ArrayList<>()).add(snap.tick());
                }
                prevCarrying.put(ws.tag(), ws.carrying());
            }
        }
        return deliveryTicks;
    }

    private static Map<String, List<Integer>> deriveCycleLengths(Map<String, List<Integer>> deliveryTicks) {
        Map<String, List<Integer>> cycleLengths = new LinkedHashMap<>();
        for (var entry : deliveryTicks.entrySet()) {
            List<Integer> ticks = entry.getValue();
            List<Integer> cycles = new ArrayList<>();
            for (int i = 1; i < ticks.size(); i++) {
                cycles.add(ticks.get(i) - ticks.get(i - 1));
            }
            cycleLengths.put(entry.getKey(), cycles);
        }
        return cycleLengths;
    }

    private static <T extends Number> double stddev(List<T> values) {
        if (values.size() < 2) return 0;
        double mean = values.stream().mapToDouble(Number::doubleValue).average().orElse(0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
                .sum() / (values.size() - 1);
        return Math.sqrt(variance);
    }

    private static WorkerCadenceBaseline loadCadenceBaseline(String resourcePath) {
        try (InputStream is = CycleVarianceDiagnosticTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Baseline not found: " + resourcePath);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return WorkerCadenceBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline: " + resourcePath, e);
        }
    }
}
