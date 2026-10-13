package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.factory.RepContent;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag("diagnostic")
class HellionDeficitDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
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
    void hellionDeficitDiagnostic() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        System.out.println("\n=== Hellion Deficit Diagnostic ===\n");

        int deficitCount = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    EmulatedGame emulated = new EmulatedGame();
                    emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));

                    DivergenceReport report = ReplayValidationHarness.run(
                        groundTruth, intents, TICK_LIMIT, emulated, race);

                    int tickIdx5 = 5 * TICKS_PER_MINUTE - 1;
                    if (tickIdx5 >= report.ticks().size()) continue;

                    var snap5 = report.ticks().get(tickIdx5);
                    int gt5 = snap5.groundTruthUnitsByType().getOrDefault(UnitType.HELLION, 0);
                    int em5 = snap5.emulatedUnitsByType().getOrDefault(UnitType.HELLION, 0);
                    int deficit = gt5 - em5;

                    if (deficit <= 0) continue;
                    deficitCount++;

                    String fileName = replayPath.getFileName().toString();
                    System.out.printf("--- Replay: %s, Player %d (race=%s) ---%n", fileName, playerId, race);
                    System.out.printf("  5-min Hellion: GT=%d, Em=%d, Deficit=%d%n", gt5, em5, deficit);

                    // Checkpoint counts for all 4 checkpoints
                    System.out.print("  Checkpoints: ");
                    for (int cp : CHECKPOINTS_MIN) {
                        int ti = cp * TICKS_PER_MINUTE - 1;
                        if (ti >= report.ticks().size()) { System.out.printf("%dmin=N/A ", cp); continue; }
                        var s = report.ticks().get(ti);
                        int g = s.groundTruthUnitsByType().getOrDefault(UnitType.HELLION, 0);
                        int e = s.emulatedUnitsByType().getOrDefault(UnitType.HELLION, 0);
                        System.out.printf("%dmin=GT:%d/Em:%d ", cp, g, e);
                    }
                    System.out.println();

                    // Building configuration at 5-min: Factories from GT snapshot
                    var gtSnap5 = report.ticks().get(tickIdx5);
                    List<Building> gtFactories = new ArrayList<>();
                    // GT buildings at 5-min come from the snapshot
                    // But the TickSnapshot only has counts by type, not the Building list
                    // We need to use emulated.snapshot() instead — but the harness already ran
                    // Use the EmulatedGame's snapshot which has the synced buildings
                    var emSnap = emulated.snapshot();
                    List<Building> emFactories = emSnap.myBuildings().stream()
                        .filter(b -> b.type() == BuildingType.FACTORY && b.isComplete())
                        .toList();
                    Set<String> reactoredTags = groundTruth.reactoredBuildingTags();

                    System.out.printf("  Factories at end (Em): %d total%n", emFactories.size());
                    for (Building f : emFactories) {
                        boolean reactored = emulated.isReactored(f.tag());
                        System.out.printf("    %s — %s%n", f.tag(), reactored ? "REACTORED" : "plain");
                    }
                    System.out.printf("  GT reactored tags: %s%n", reactoredTags.stream()
                        .sorted().collect(Collectors.joining(", ")));

                    // Hellion train intents: commandLoop values
                    List<Long> hellionLoops = intents.stream()
                        .filter(ti -> ti.intent() instanceof TrainIntent t && t.unitType() == UnitType.HELLION)
                        .map(TimedIntent::loop)
                        .sorted()
                        .toList();
                    long fiveMinLoops = (long)(5 * 60 * SC2Data.GAME_LOOPS_PER_SECOND);
                    long before5Count = hellionLoops.stream().filter(l -> l < fiveMinLoops).count();

                    System.out.printf("  Hellion intents: %d total, %d before 5-min%n",
                        hellionLoops.size(), before5Count);
                    System.out.print("  Intent loops (before 5-min): ");
                    hellionLoops.stream().filter(l -> l < fiveMinLoops)
                        .forEach(l -> System.out.printf("%d ", l));
                    System.out.println();

                    // Spacing between consecutive intents
                    if (before5Count > 1) {
                        System.out.print("  Inter-intent spacing (loops): ");
                        List<Long> before5Loops = hellionLoops.stream()
                            .filter(l -> l < fiveMinLoops).toList();
                        for (int i = 1; i < before5Loops.size(); i++) {
                            System.out.printf("%d ", before5Loops.get(i) - before5Loops.get(i - 1));
                        }
                        System.out.println();
                    }

                    // Train diagnostics for HELLION in this replay
                    int[] diag = emulated.trackerTrainDiagnostics().get(UnitType.HELLION);
                    if (diag != null) {
                        System.out.printf("  Diagnostics: received=%d trained=%d bldg404=%d queueFull=%d overflow=%d%n",
                            diag[0], diag[1], diag[2], diag[5], diag[6]);
                    }

                    // Also show other unit types produced in this replay for context
                    System.out.print("  Other units at 5-min (GT): ");
                    snap5.groundTruthUnitsByType().entrySet().stream()
                        .filter(e -> e.getValue() > 0)
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(8)
                        .forEach(e -> System.out.printf("%s=%d ", e.getKey(), e.getValue()));
                    System.out.println();
                    System.out.println();

                } catch (Exception e) { /* skip failed replays */ }
            }
        }

        System.out.printf("=== Total replays with Hellion deficit at 5-min: %d ===%n", deficitCount);
    }
}
