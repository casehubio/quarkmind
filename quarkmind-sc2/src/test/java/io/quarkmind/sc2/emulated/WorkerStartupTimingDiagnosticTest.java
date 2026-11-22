package io.quarkmind.sc2.emulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import io.quarkmind.sc2.replay.TrackerIntentExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.DoubleSummaryStatistics;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures per-worker startup delay (birth → first delivery) in both
 * controlled calibration and standalone oracle scenarios.
 *
 * Refs #746
 */
@Tag("diagnostic")
class WorkerStartupTimingDiagnosticTest {

    private static final float GAS_PER_WORKER_PER_MIN = 38.0f;

    @Test
    void controlledWorkerStartupTiming() {
        System.out.println("=== Worker Startup Timing — Controlled 12w Mineral Scenario ===\n");

        CalibrationBaseline sc2Baseline = loadBaseline("calibration/cal-mineral-baseline-12w.json");

        System.out.println("--- SC2 baseline mineral income (first 20 ticks) ---");
        System.out.printf("%-6s %8s %8s%n", "tick", "minerals", "delta");
        int prevSc2 = 0;
        for (int t = 0; t < Math.min(20, sc2Baseline.snapshots().size()); t++) {
            int m = sc2Baseline.snapshots().get(t).minerals();
            System.out.printf("%-6d %8d %+8d%n", t, m, m - prevSc2);
            prevSc2 = m;
        }

        System.out.println("\n--- EM with stagger (reset path) ---");
        Map<String, Double> staggerDeliveries = new LinkedHashMap<>();
        EmulatedGame gameStagger = new EmulatedGame();
        gameStagger.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        gameStagger.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
            @Override public void onPhaseChange(String t, WorkerPhase f, WorkerPhase to, double l) {}
            @Override public void onDelivery(String tag, double minerals, double loop) {
                staggerDeliveries.putIfAbsent(tag, loop);
            }
        });
        gameStagger.reset();

        System.out.printf("%-6s %8s %8s%n", "tick", "minerals", "delta");
        int prevEm = 50;
        for (int tick = 0; tick < 20; tick++) {
            gameStagger.tick();
            int m = gameStagger.snapshot().minerals();
            System.out.printf("%-6d %8d %+8d%n", tick, m, m - prevEm);
            prevEm = m;
        }

        System.out.println("\nPer-worker first delivery (stagger path):");
        System.out.printf("%-20s %12s %8s%n", "tag", "loop", "tick");
        DoubleSummaryStatistics staggerStats = new DoubleSummaryStatistics();
        for (var e : staggerDeliveries.entrySet()) {
            double loop = e.getValue();
            staggerStats.accept(loop);
            System.out.printf("%-20s %12.0f %8.1f%n", e.getKey(), loop, loop / SC2Data.LOOPS_PER_TICK);
        }

        System.out.println("\n--- EM without stagger (standalone/resetForHarness path) ---");
        Map<String, Double> noStaggerDeliveries = new LinkedHashMap<>();
        EmulatedGame gameNoStagger = new EmulatedGame();
        gameNoStagger.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        gameNoStagger.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
            @Override public void onPhaseChange(String t, WorkerPhase f, WorkerPhase to, double l) {}
            @Override public void onDelivery(String tag, double minerals, double loop) {
                noStaggerDeliveries.putIfAbsent(tag, loop);
            }
        });
        gameNoStagger.reset();
        List<String> tags = gameNoStagger.snapshot().myUnits().stream()
            .filter(u -> u.type() == gameNoStagger.playerRaceModel().workerType())
            .map(io.quarkmind.domain.Unit::tag).toList();
        gameNoStagger.resetForHarness();
        gameNoStagger.workerSimulation().onTownHallCompleted("th-1", new io.quarkmind.domain.Point2d(8, 8));
        for (String tag : tags) {
            gameNoStagger.workerSimulation().onWorkerProduced(tag, new io.quarkmind.domain.Point2d(8, 8));
        }

        System.out.printf("%-6s %8s %8s%n", "tick", "minerals", "delta");
        prevEm = 50;
        for (int tick = 0; tick < 20; tick++) {
            gameNoStagger.tick();
            int m = gameNoStagger.snapshot().minerals();
            System.out.printf("%-6d %8d %+8d%n", tick, m, m - prevEm);
            prevEm = m;
        }

        System.out.println("\nPer-worker first delivery (no-stagger path):");
        System.out.printf("%-20s %12s %8s%n", "tag", "loop", "tick");
        DoubleSummaryStatistics noStaggerStats = new DoubleSummaryStatistics();
        for (var e : noStaggerDeliveries.entrySet()) {
            double loop = e.getValue();
            noStaggerStats.accept(loop);
            System.out.printf("%-20s %12.0f %8.1f%n", e.getKey(), loop, loop / SC2Data.LOOPS_PER_TICK);
        }

        System.out.println("\n--- Summary ---");
        System.out.printf("Stagger path:    first=%.0f  last=%.0f  avg=%.0f  range=%.0f loops%n",
            staggerStats.getMin(), staggerStats.getMax(), staggerStats.getAverage(),
            staggerStats.getMax() - staggerStats.getMin());
        System.out.printf("No-stagger path: first=%.0f  last=%.0f  avg=%.0f  range=%.0f loops%n",
            noStaggerStats.getMin(), noStaggerStats.getMax(), noStaggerStats.getAverage(),
            noStaggerStats.getMax() - noStaggerStats.getMin());
        System.out.printf("SC2 first income: tick 6 (minerals 50→60, loop ~%d)%n",
            6 * SC2Data.LOOPS_PER_TICK);
    }

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void standaloneFirstDeliveryTiming() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().limit(30).toList();
        }

        System.out.println("=== Standalone Worker Startup — First 10 Ticks EM vs GT ===\n");

        int[] ticksChecked = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Map<Integer, long[]> mineralAccum = new HashMap<>();
        for (int t : ticksChecked) mineralAccum.put(t, new long[]{0, 0, 0});

        DoubleSummaryStatistics firstDeliveryStats = new DoubleSummaryStatistics();
        int totalRuns = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    ReplaySimulatedGame gt = new ReplaySimulatedGame(replayPath, playerId);
                    EmulatedGame em = new EmulatedGame();
                    em.setPlayerRaceModel(RaceModelFactory.forRace(race));

                    Map<String, Double> firstDeliveries = new HashMap<>();
                    em.workerSimulation().setPhaseListener(new WorkerPhaseListener() {
                        @Override public void onPhaseChange(String t, WorkerPhase f, WorkerPhase to, double l) {}
                        @Override public void onDelivery(String tag, double minerals, double loop) {
                            firstDeliveries.putIfAbsent(tag, loop);
                        }
                    });

                    gt.reset();
                    em.setMineralPatchResources(gt.getMineralPatches());
                    em.resetForHarness();

                    int cursor = 0;
                    for (int tick = 0; tick < 10; tick++) {
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
                            int gw = Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN));
                            em.syncTotalGasWorkers(gw);
                        }

                        GameState emSnap = em.snapshot();
                        for (int t : ticksChecked) {
                            if (t == tick) {
                                long[] acc = mineralAccum.get(t);
                                acc[0] += gtSnap.minerals();
                                acc[1] += emSnap.minerals();
                                acc[2]++;
                            }
                        }
                    }

                    for (double loop : firstDeliveries.values()) {
                        firstDeliveryStats.accept(loop);
                    }
                    totalRuns++;
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.printf("Replays: %d, player runs: %d%n%n", replays.size(), totalRuns);

        System.out.printf("%-6s %10s %10s %10s %10s%n", "tick", "GT avg", "EM avg", "delta", "pct");
        for (int t : ticksChecked) {
            long[] acc = mineralAccum.get(t);
            if (acc[2] == 0) continue;
            double gtAvg = (double) acc[0] / acc[2];
            double emAvg = (double) acc[1] / acc[2];
            double delta = emAvg - gtAvg;
            double pct = gtAvg != 0 ? delta / gtAvg * 100 : 0;
            System.out.printf("%-6d %10.0f %10.0f %+10.0f %+9.1f%%%n",
                t, gtAvg, emAvg, delta, pct);
        }

        System.out.println("\n--- Standalone EM first delivery timing ---");
        System.out.printf("Workers tracked: %d%n", firstDeliveryStats.getCount());
        System.out.printf("First delivery loop: min=%.0f  avg=%.0f  max=%.0f%n",
            firstDeliveryStats.getMin(), firstDeliveryStats.getAverage(), firstDeliveryStats.getMax());
        System.out.printf("First delivery tick: min=%.1f  avg=%.1f  max=%.1f%n",
            firstDeliveryStats.getMin() / SC2Data.LOOPS_PER_TICK,
            firstDeliveryStats.getAverage() / SC2Data.LOOPS_PER_TICK,
            firstDeliveryStats.getMax() / SC2Data.LOOPS_PER_TICK);
    }

    private static CalibrationBaseline loadBaseline(String resourcePath) {
        try (InputStream is = WorkerStartupTimingDiagnosticTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Baseline not found: " + resourcePath);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return CalibrationBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load baseline: " + resourcePath, e);
        }
    }
}
