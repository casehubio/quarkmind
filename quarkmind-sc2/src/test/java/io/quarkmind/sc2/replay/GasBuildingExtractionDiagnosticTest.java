package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.BuildingBornIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.mock.Sc2ReplayShared;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Tag("diagnostic")
class GasBuildingExtractionDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
            "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int LOOPS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND);
    private static final int FIVE_MIN_LOOPS = 5 * LOOPS_PER_MINUTE;
    private static final int TICKS_PER_MINUTE =
            (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void compareTrackerEventsVsExtractedIntents() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        int totalRuns = 0;
        int trackerGasEvents = 0;
        int extractedGasIntents = 0;
        int mismatchCount = 0;
        List<String> mismatches = new ArrayList<>();

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS, RepContent.GAME_EVENTS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null || replay.trackerEvents == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                totalRuns++;

                int rawGasInitCount = 0;
                int rawGasBornCount = 0;
                List<String> rawGasNames = new ArrayList<>();

                for (Event raw : replay.trackerEvents.getEvents()) {
                    if (raw.getLoop() > FIVE_MIN_LOOPS) break;
                    int id = raw.getId();
                    if (id == ITrackerEvents.ID_UNIT_INIT || id == ITrackerEvents.ID_UNIT_BORN) {
                        if (!(raw instanceof IBaseUnitEvent ue)) continue;
                        Integer pid = ue.getControlPlayerId();
                        if (pid == null || pid != playerId) continue;
                        String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                        BuildingType bt = Sc2ReplayShared.toBuildingType(typeName);
                        if (SC2Data.isGasBuilding(bt)) {
                            if (id == ITrackerEvents.ID_UNIT_INIT) rawGasInitCount++;
                            else rawGasBornCount++;
                            rawGasNames.add(typeName + "@" + raw.getLoop() +
                                (id == ITrackerEvents.ID_UNIT_INIT ? "(init)" : "(born)"));
                        }
                    }
                }

                List<TimedIntent> intents = TrackerIntentExtractor.extractFromReplay(replay, playerId);

                int intentBuildCount = 0;
                int intentBornCount = 0;
                for (TimedIntent ti : intents) {
                    if (ti.loop() > FIVE_MIN_LOOPS) continue;
                    if (ti.intent() instanceof BuildIntent bi && SC2Data.isGasBuilding(bi.buildingType())) {
                        intentBuildCount++;
                    } else if (ti.intent() instanceof BuildingBornIntent bb && SC2Data.isGasBuilding(bb.type())) {
                        intentBornCount++;
                    }
                }

                int rawTotal = rawGasInitCount + rawGasBornCount;
                int intentTotal = intentBuildCount + intentBornCount;

                trackerGasEvents += rawTotal;
                extractedGasIntents += intentTotal;

                if (rawTotal != intentTotal) {
                    mismatchCount++;
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);
                    mismatches.add(String.format(
                        "  %s P%d %s — raw:%d (init:%d born:%d) vs intent:%d (build:%d born:%d) — events: %s",
                        replayPath.getFileName(), playerId, race,
                        rawTotal, rawGasInitCount, rawGasBornCount,
                        intentTotal, intentBuildCount, intentBornCount,
                        rawGasNames));
                }
            }
        }

        System.out.println("=== Gas Building Extraction Diagnostic (#671) ===");
        System.out.printf("Total player runs: %d%n", totalRuns);
        System.out.printf("Total raw gas tracker events (≤5min): %d%n", trackerGasEvents);
        System.out.printf("Total extracted gas intents (≤5min): %d%n", extractedGasIntents);
        System.out.printf("Mismatches: %d / %d (%.1f%%)%n%n",
            mismatchCount, totalRuns, mismatchCount * 100.0 / totalRuns);

        if (!mismatches.isEmpty()) {
            System.out.println("--- Mismatch details (first 30) ---");
            mismatches.stream().limit(30).forEach(System.out::println);
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void traceGasBuildingsThroughEmulatedGame() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        int          totalRuns              = 0;
        int[][]      emGasBuildings         = new int[CHECKPOINTS_MIN.length][3];
        int[][]      gtGasBuildings         = new int[CHECKPOINTS_MIN.length][3];
        int[][]      emGasBuildingsComplete = new int[CHECKPOINTS_MIN.length][3];
        int[][]      emGasSlots             = new int[CHECKPOINTS_MIN.length][3];
        int[][]      mismatchDetails        = new int[CHECKPOINTS_MIN.length][3];
        List<String> worstReplays           = new ArrayList<>();

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                                                     EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS, RepContent.GAME_EVENTS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.details == null || replay.trackerEvents == null) {continue;}
            if (replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    totalRuns++;
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent>   intents     = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race                race        = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);
                    int                 ri          = race == Race.PROTOSS ? 0 : race == Race.TERRAN ? 1 : 2;

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));
                    groundTruth.reset();
                    emulated.setMineralPatchResources(groundTruth.getMineralPatches());
                    emulated.resetForHarness();

                    int cursor = 0;

                    for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
                        long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;
                        emulated.tick();
                        groundTruth.tick();

                        emulated.processDeferredTrainIntents();
                        emulated.processDeferredResearchIntents();

                        while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                            emulated.applyIntent(intents.get(cursor));
                            cursor++;
                        }

                        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
                            if (tick == CHECKPOINTS_MIN[i] * TICKS_PER_MINUTE - 1) {
                                GameState gt = groundTruth.snapshot();
                                GameState em = emulated.snapshot();

                                int gtGasCount = (int) gt.myBuildings().stream()
                                                         .filter(b -> SC2Data.isGasBuilding(b.type()) && b.isComplete()).count();
                                int emGasBuildingCount = (int) em.myBuildings().stream()
                                                                 .filter(b -> SC2Data.isGasBuilding(b.type())).count();
                                int emGasCompleteCount = (int) em.myBuildings().stream()
                                                                 .filter(b -> SC2Data.isGasBuilding(b.type()) && b.isComplete()).count();
                                int emSlotCount = emulated.gasSlotCount();

                                gtGasBuildings[i][ri] +=         gtGasCount;
                                emGasBuildings[i][ri] +=         emGasBuildingCount;
                                emGasBuildingsComplete[i][ri] += emGasCompleteCount;
                                emGasSlots[i][ri] +=             emSlotCount;

                                if (emSlotCount < gtGasCount) {
                                    mismatchDetails[i][ri]++;
                                }

                                if (i == 2 && emSlotCount < gtGasCount) {
                                    int delta        = gtGasCount - emSlotCount;
                                    int emIncomplete = emGasBuildingCount - emGasCompleteCount;
                                    worstReplays.add(String.format(
                                            "  %s P%d %s — GT:%d EM_all:%d EM_complete:%d EM_slots:%d (delta:%d, incomplete:%d)",
                                            replayPath.getFileName(), playerId, race,
                                            gtGasCount, emGasBuildingCount, emGasCompleteCount, emSlotCount,
                                            delta, emIncomplete));
                                }
                            }
                        }
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("\n=== Gas Building Pipeline Trace (#671) ===");
        System.out.printf("Total player runs: %d%n%n", totalRuns);

        String[] raceNames = {"PROTOSS", "TERRAN", "ZERG"};

        System.out.printf("%-5s %-10s %8s %8s %8s %8s %8s%n",
                          "CP", "Race", "GT_gas", "EM_all", "EM_done", "EM_slot", "EM<GT");
        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            for (int ri = 0; ri < 3; ri++) {
                System.out.printf("%-5d %-10s %8d %8d %8d %8d %8d%n",
                                  CHECKPOINTS_MIN[i], raceNames[ri],
                                  gtGasBuildings[i][ri], emGasBuildings[i][ri],
                                  emGasBuildingsComplete[i][ri], emGasSlots[i][ri],
                                  mismatchDetails[i][ri]);
            }
        }

        System.out.println();
        System.out.println("--- Aggregated ---");
        System.out.printf("%-5s %8s %8s %8s %8s %8s%n",
                          "CP", "GT_gas", "EM_all", "EM_done", "EM_slot", "EM<GT");
        for (int i = 0; i < CHECKPOINTS_MIN.length; i++) {
            int gtTotal = 0, emAll = 0, emDone = 0, emSlot = 0, mismatch = 0;
            for (int ri = 0; ri < 3; ri++) {
                gtTotal += gtGasBuildings[i][ri];
                emAll += emGasBuildings[i][ri];
                emDone += emGasBuildingsComplete[i][ri];
                emSlot += emGasSlots[i][ri];
                mismatch += mismatchDetails[i][ri];
            }
            System.out.printf("%-5d %8d %8d %8d %8d %8d%n",
                              CHECKPOINTS_MIN[i], gtTotal, emAll, emDone, emSlot, mismatch);
        }

        if (!worstReplays.isEmpty()) {
            System.out.println("\n--- 5-min mismatches (first 30) ---");
            worstReplays.stream().limit(30).forEach(System.out::println);
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void measureGeyserDistancesPerBase() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().toList();
        }

        List<Double> allDistances = new ArrayList<>();
        int          pairsUnder5  = 0;
        int          pairsUnder6  = 0;
        int          totalPairs   = 0;
        List<String> closePairs   = new ArrayList<>();

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                                                     EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) {continue;}
            if (replay == null || replay.trackerEvents == null) {continue;}
            if (replay.details == null || replay.details.getPlayerList().length < 2) {continue;}

            for (int playerId = 1; playerId <= 2; playerId++) {
                List<Point2d> gasPositions = new ArrayList<>();
                for (Event raw : replay.trackerEvents.getEvents()) {
                    if (raw.getLoop() > FIVE_MIN_LOOPS) {break;}
                    int id = raw.getId();
                    if (id == ITrackerEvents.ID_UNIT_INIT || id == ITrackerEvents.ID_UNIT_BORN) {
                        if (!(raw instanceof IBaseUnitEvent ue)) {continue;}
                        Integer pid = ue.getControlPlayerId();
                        if (pid == null || pid != playerId) {continue;}
                        String       typeName = String.valueOf(ue.getUnitTypeName()).trim();
                        BuildingType bt       = Sc2ReplayShared.toBuildingType(typeName);
                        if (SC2Data.isGasBuilding(bt)) {
                            gasPositions.add(new Point2d(ue.getXCoord(), ue.getYCoord()));
                        }
                    }
                }

                for (int i = 0; i < gasPositions.size(); i++) {
                    for (int j = i + 1; j < gasPositions.size(); j++) {
                        double dist = gasPositions.get(i).distanceTo(gasPositions.get(j));
                        allDistances.add(dist);
                        totalPairs++;
                        if (dist < 5.0) {
                            pairsUnder5++;
                            closePairs.add(String.format("  %s P%d — (%.1f,%.1f)↔(%.1f,%.1f) = %.2f",
                                                         replayPath.getFileName(), playerId,
                                                         gasPositions.get(i).x(), gasPositions.get(i).y(),
                                                         gasPositions.get(j).x(), gasPositions.get(j).y(), dist));
                        }
                        if (dist < 6.0) {pairsUnder6++;}
                    }
                }
            }
        }

        System.out.println("\n=== Geyser Distance Analysis (#671) ===");
        System.out.printf("Total geyser pairs: %d%n", totalPairs);
        System.out.printf("Pairs < 5.0u: %d (%.1f%%)%n", pairsUnder5, pairsUnder5 * 100.0 / totalPairs);
        System.out.printf("Pairs < 6.0u: %d (%.1f%%)%n", pairsUnder6, pairsUnder6 * 100.0 / totalPairs);

        allDistances.sort(Double::compareTo);
        if (!allDistances.isEmpty()) {
            System.out.printf("Min: %.2f  P5: %.2f  P25: %.2f  Median: %.2f  Max: %.2f%n",
                              allDistances.get(0),
                              allDistances.get(allDistances.size() / 20),
                              allDistances.get(allDistances.size() / 4),
                              allDistances.get(allDistances.size() / 2),
                              allDistances.get(allDistances.size() - 1));
        }

        if (!closePairs.isEmpty()) {
            System.out.println("\n--- Pairs < 5.0u (first 20) ---");
            closePairs.stream().limit(20).forEach(System.out::println);
        }
    }
}
