package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Tag("diagnostic")
class StandaloneRejectionDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final float GAS_PER_WORKER_PER_MIN = 38.0f;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void standaloneRejectionFunnel() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        AtomicInteger totalIntents = new AtomicInteger();
        AtomicInteger totalAccepted = new AtomicInteger();
        AtomicInteger totalRejected = new AtomicInteger();
        AtomicLong rejectedMinerals = new AtomicLong();
        AtomicLong rejectedGas = new AtomicLong();
        Map<String, int[]> reasonCounts = new TreeMap<>();
        Map<UnitType, int[]> perTypeCounts = new TreeMap<>();
        int totalRuns = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            String mapName = ReplayValidationHarness.resolveMapName(replayPath);

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
                    ReplayValidationHarness.loadTerrainFromCache(emulated, mapName);
                    groundTruth.reset();
                    emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                    emulated.resetForHarness();

                    emulated.setIntentExecutionListener((tick, intent, bldgTag, success, reason) -> {
                        totalIntents.incrementAndGet();
                        if (success) {
                            totalAccepted.incrementAndGet();
                        } else {
                            totalRejected.incrementAndGet();
                            int minCost = SC2Data.mineralCost(intent.unitType());
                            int gasCost = SC2Data.gasCost(intent.unitType());
                            rejectedMinerals.addAndGet(minCost);
                            rejectedGas.addAndGet(gasCost);
                            String r = reason != null ? reason : "unknown";
                            reasonCounts.computeIfAbsent(r, k -> new int[]{0, 0})[0]++;
                            reasonCounts.get(r)[1] += minCost;
                            int[] tc = perTypeCounts.computeIfAbsent(intent.unitType(), k -> new int[]{0, 0, 0});
                            tc[0]++;
                            tc[1] += minCost;
                            tc[2] += gasCost;
                        }
                    });

                    int cursor = 0;
                    totalRuns++;

                    for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
                        long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

                        emulated.tick();
                        groundTruth.tick();
                        GameState gt = groundTruth.snapshot();

                        emulated.processDeferredTrainIntents();
                        emulated.processDeferredResearchIntents();

                        while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                            emulated.applyIntent(intents.get(cursor));
                            cursor++;
                        }

                        int gtGasRate = gt.playerEconomy().vespeneCollectionRate();
                        if (gtGasRate > 0) {
                            int estimatedGasWorkers = Math.max(1, Math.round(gtGasRate / GAS_PER_WORKER_PER_MIN));
                            emulated.syncTotalGasWorkers(estimatedGasWorkers);
                        }
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        }

        System.out.println("\n=== Standalone Intent Rejection Funnel (#761) ===");
        System.out.printf("Player runs: %d%n", totalRuns);
        System.out.printf("Total TrainIntents: %d%n", totalIntents.get());
        System.out.printf("  Accepted: %d (%.1f%%)%n", totalAccepted.get(),
            totalIntents.get() > 0 ? totalAccepted.get() * 100.0 / totalIntents.get() : 0);
        System.out.printf("  Rejected: %d (%.1f%%)%n", totalRejected.get(),
            totalIntents.get() > 0 ? totalRejected.get() * 100.0 / totalIntents.get() : 0);
        System.out.printf("  Rejected minerals: %d (avg %.0f/run)%n",
            rejectedMinerals.get(), totalRuns > 0 ? (double) rejectedMinerals.get() / totalRuns : 0);
        System.out.printf("  Rejected gas: %d (avg %.0f/run)%n",
            rejectedGas.get(), totalRuns > 0 ? (double) rejectedGas.get() / totalRuns : 0);

        System.out.println("\nBy rejection reason:");
        System.out.printf("  %-25s | %6s | %10s%n", "Reason", "Count", "Minerals");
        System.out.println("  " + "-".repeat(50));
        for (var entry : reasonCounts.entrySet()) {
            System.out.printf("  %-25s | %6d | %10d%n",
                entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }

        System.out.println("\nTop rejected unit types by mineral cost:");
        perTypeCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]))
            .limit(15)
            .forEach(e -> System.out.printf("  %-15s | %4d rejected | %6d minerals | %5d gas%n",
                e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
    }
}
