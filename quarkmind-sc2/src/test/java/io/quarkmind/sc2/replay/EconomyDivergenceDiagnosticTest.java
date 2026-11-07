package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.TimedIntent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Per-replay economy divergence diagnostic — identifies worst-case replays
 * for mineralsCurrent and vespeneCurrent to guide targeted accuracy improvements.
 *
 * Refs #553
 */
@Tag("diagnostic")
class EconomyDivergenceDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final int TOP_N = 20;

    record ReplayEconomyEntry(
        String replayFile,
        int playerId,
        Race race,
        int checkpoint,
        int gtMinerals, int emMinerals, int mineralAbsError,
        int gtVespene, int emVespene, int vespeneAbsError,
        int gtMineralRate, int emMineralRate,
        int gtVespeneRate, int emVespeneRate,
        int gtWorkers, int emWorkers,
        double gtSupplyUsed, double emSupplyUsed) {}

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void perReplayEconomyDivergence() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        List<ReplayEconomyEntry> allEntries = new ArrayList<>();

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
                    io.quarkmind.sc2.mock.ReplaySimulatedGame groundTruth =
                        new io.quarkmind.sc2.mock.ReplaySimulatedGame(replayPath, playerId);

                    List<DivergenceReport.TickSnapshot> snapshots =
                        StandaloneEmulatedGameBaselineTest.runStandalone(
                            groundTruth, intents, TICK_LIMIT, race);

                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= snapshots.size()) continue;
                        var snap = snapshots.get(tickIndex);
                        if (snap.groundTruthEconomy() == null || snap.emulatedEconomy() == null) continue;

                        PlayerEconomyStats gt = snap.groundTruthEconomy();
                        PlayerEconomyStats em = snap.emulatedEconomy();

                        allEntries.add(new ReplayEconomyEntry(
                            replayPath.getFileName().toString(),
                            playerId,
                            race,
                            cp,
                            gt.mineralsCurrent(), em.mineralsCurrent(),
                            Math.abs(gt.mineralsCurrent() - em.mineralsCurrent()),
                            gt.vespeneCurrent(), em.vespeneCurrent(),
                            Math.abs(gt.vespeneCurrent() - em.vespeneCurrent()),
                            gt.mineralsCollectionRate(), em.mineralsCollectionRate(),
                            gt.vespeneCollectionRate(), em.vespeneCollectionRate(),
                            gt.workersActiveCount(), em.workersActiveCount(),
                            gt.foodUsed(), em.foodUsed()));
                    }
                } catch (Exception e) { /* skip failed replays */ }
            }
        }

        for (int cp : CHECKPOINTS_MIN) {
            List<ReplayEconomyEntry> atCheckpoint = allEntries.stream()
                .filter(e -> e.checkpoint == cp).toList();

            System.out.printf("%n=== %d-minute checkpoint (%d player runs) ===%n%n", cp, atCheckpoint.size());

            printTopN("mineralsCurrent", atCheckpoint,
                Comparator.comparingInt(ReplayEconomyEntry::mineralAbsError).reversed());

            printTopN("vespeneCurrent", atCheckpoint,
                Comparator.comparingInt(ReplayEconomyEntry::vespeneAbsError).reversed());

            printRaceBreakdown(atCheckpoint);
            printDirectionBreakdown(atCheckpoint);
        }
    }

    private void printTopN(String field, List<ReplayEconomyEntry> entries,
                           Comparator<ReplayEconomyEntry> comparator) {
        List<ReplayEconomyEntry> sorted = entries.stream()
            .sorted(comparator).limit(TOP_N).toList();

        System.out.printf("--- Top %d worst replays for %s ---%n", TOP_N, field);
        System.out.printf("| %-40s | P | Race | GT    | EM    | Error | MnRate GT/EM | GasRate GT/EM | Workers GT/EM | Supply GT/EM |%n",
            "Replay");
        System.out.printf("|%-42s|---|------|-------|-------|-------|--------------|---------------|---------------|--------------|%n",
            "-".repeat(42));

        boolean isMinerals = field.equals("mineralsCurrent");
        for (ReplayEconomyEntry e : sorted) {
            int error = isMinerals ? e.mineralAbsError : e.vespeneAbsError;
            int gt = isMinerals ? e.gtMinerals : e.gtVespene;
            int em = isMinerals ? e.emMinerals : e.emVespene;
            System.out.printf("| %-40s | %d | %-4s | %5d | %5d | %5d | %5d / %-5d | %5d / %-5d | %5d / %-5d | %5.0f / %-5.0f |%n",
                truncate(e.replayFile, 40), e.playerId, e.race,
                gt, em, error,
                e.gtMineralRate, e.emMineralRate,
                e.gtVespeneRate, e.emVespeneRate,
                e.gtWorkers, e.emWorkers,
                e.gtSupplyUsed, e.emSupplyUsed);
        }
        System.out.println();
    }

    private void printRaceBreakdown(List<ReplayEconomyEntry> entries) {
        Map<Race, List<ReplayEconomyEntry>> byRace = new EnumMap<>(Race.class);
        for (ReplayEconomyEntry e : entries) {
            byRace.computeIfAbsent(e.race, k -> new ArrayList<>()).add(e);
        }

        System.out.println("--- Per-race economy error ---");
        System.out.println("| Race     | Count | Mineral Mean Err | Mineral MAPE | Vespene Mean Err | Vespene MAPE |");
        System.out.println("|----------|-------|------------------|--------------|------------------|--------------|");

        for (Race race : Race.values()) {
            List<ReplayEconomyEntry> raceEntries = byRace.getOrDefault(race, List.of());
            if (raceEntries.isEmpty()) continue;

            double mineralMeanErr = raceEntries.stream().mapToInt(ReplayEconomyEntry::mineralAbsError).average().orElse(0);
            double vespeneMeanErr = raceEntries.stream().mapToInt(ReplayEconomyEntry::vespeneAbsError).average().orElse(0);
            double mineralMape = computeMape(raceEntries, ReplayEconomyEntry::gtMinerals, ReplayEconomyEntry::mineralAbsError);
            double vespeneMape = computeMape(raceEntries, ReplayEconomyEntry::gtVespene, ReplayEconomyEntry::vespeneAbsError);

            System.out.printf("| %-8s | %5d | %16.1f | %10.1f%% | %16.1f | %10.1f%% |%n",
                race, raceEntries.size(), mineralMeanErr, mineralMape, vespeneMeanErr, vespeneMape);
        }
        System.out.println();
    }

    private void printDirectionBreakdown(List<ReplayEconomyEntry> entries) {
        long mineralOver = entries.stream().filter(e -> e.emMinerals > e.gtMinerals).count();
        long mineralUnder = entries.stream().filter(e -> e.emMinerals < e.gtMinerals).count();
        long mineralExact = entries.stream().filter(e -> e.emMinerals == e.gtMinerals).count();
        long vespeneOver = entries.stream().filter(e -> e.emVespene > e.gtVespene).count();
        long vespeneUnder = entries.stream().filter(e -> e.emVespene < e.gtVespene).count();
        long vespeneExact = entries.stream().filter(e -> e.emVespene == e.gtVespene).count();

        System.out.println("--- Error direction ---");
        System.out.printf("mineralsCurrent: %d over / %d under / %d exact%n", mineralOver, mineralUnder, mineralExact);
        System.out.printf("vespeneCurrent:  %d over / %d under / %d exact%n%n", vespeneOver, vespeneUnder, vespeneExact);
    }

    private static double computeMape(List<ReplayEconomyEntry> entries,
                                      java.util.function.ToIntFunction<ReplayEconomyEntry> gtFn,
                                      java.util.function.ToIntFunction<ReplayEconomyEntry> errFn) {
        double sumGt = entries.stream().mapToInt(gtFn).sum();
        double sumErr = entries.stream().mapToInt(errFn).sum();
        return sumGt != 0 ? sumErr / sumGt * 100.0 : 0;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
