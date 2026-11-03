package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.replay.DivergenceReport;
import io.quarkmind.sc2.replay.ReplayValidationHarness;
import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

@Tag("diagnostic")
class GasHarvestCalibrationTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    private static final int TICKS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int CHECKPOINT_MINUTE = 5;
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * CHECKPOINT_MINUTE + 1;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void resetDefaults() {
        SimulatedWorker.resetGasHarvestDuration();
    }

    @Test
    @EnabledIf("oracleExists")
    void sweepGasHarvestDuration() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        System.out.printf("%nGas harvest duration calibration — %d replays, %d-min checkpoint%n",
                replays.size(), CHECKPOINT_MINUTE);
        System.out.printf("%-8s  %-8s  %10s  %10s  %10s  %12s%n",
                "Loops", "Ticks", "GT Rate", "Em Rate", "VespErr%", "MineralErr%");
        System.out.println("-".repeat(70));

        double bestError = Double.MAX_VALUE;
        int bestLoops = SimulatedWorker.GAS_HARVEST_DURATION_LOOPS_DEFAULT;

        for (int loops = 15; loops <= 75; loops += 5) {
            SimulatedWorker.setGasHarvestDurationLoops(loops);

            long sumGtVespeneRate = 0, sumEmVespeneRate = 0;
            long sumGtMineralRate = 0, sumEmMineralRate = 0;
            int processed = 0;

            for (Path replayPath : replays) {
                Replay replay;
                try {
                    replay = RepParserEngine.parseReplay(replayPath,
                            EnumSet.of(RepContent.DETAILS));
                } catch (Exception e) { continue; }
                if (replay == null || replay.details == null) continue;
                Player[] players = replay.details.getPlayerList();
                if (players.length < 2) continue;

                for (int playerId = 1; playerId <= 2; playerId++) {
                    try {
                        DivergenceReport report = ReplayValidationHarness.run(
                                replayPath, playerId, TICK_LIMIT);
                        int tickIndex = CHECKPOINT_MINUTE * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= report.ticks().size()) continue;
                        DivergenceReport.TickSnapshot snap = report.ticks().get(tickIndex);

                        PlayerEconomyStats gtEcon = snap.groundTruthEconomy();
                        PlayerEconomyStats emEcon = snap.emulatedEconomy();
                        if (gtEcon != null && emEcon != null) {
                            sumGtVespeneRate += gtEcon.vespeneCollectionRate();
                            sumEmVespeneRate += emEcon.vespeneCollectionRate();
                            sumGtMineralRate += gtEcon.mineralsCollectionRate();
                            sumEmMineralRate += emEcon.mineralsCollectionRate();
                        }
                    } catch (Exception e) { /* skip */ }
                }
                processed++;
            }

            double vespeneRateError = sumGtVespeneRate != 0
                    ? Math.abs((double) (sumEmVespeneRate - sumGtVespeneRate) / sumGtVespeneRate) * 100
                    : 0;
            double mineralRateError = sumGtMineralRate != 0
                    ? Math.abs((double) (sumEmMineralRate - sumGtMineralRate) / sumGtMineralRate) * 100
                    : 0;
            int ticks = (int) Math.ceil((double) loops / SC2Data.LOOPS_PER_TICK);

            System.out.printf("%-8d  %-8d  %10d  %10d  %10.1f  %12.1f%n",
                    loops, ticks, sumGtVespeneRate, sumEmVespeneRate,
                    vespeneRateError, mineralRateError);

            if (vespeneRateError < bestError) {
                bestError = vespeneRateError;
                bestLoops = loops;
            }
        }

        SimulatedWorker.resetGasHarvestDuration();
        System.out.printf("%nBest: GAS_HARVEST_DURATION_LOOPS = %d (vespene error: %.1f%%)%n",
                bestLoops, bestError);
    }
}
