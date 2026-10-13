package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Tag("diagnostic")
class HellionTickTraceDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final String TARGET_REPLAY = "3307d878e8b14fa2fc3a8c9ffbc81e19acbd73fb26e628438ce76bcaf7620d65.SC2Replay";
    private static final int TARGET_PLAYER = 1;
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    static boolean replayExists() {
        return Files.isRegularFile(ORACLE_DIR.resolve(TARGET_REPLAY));
    }

    @Test
    @EnabledIf("replayExists")
    void traceHellionCounts() throws Exception {
        Path replayPath = ORACLE_DIR.resolve(TARGET_REPLAY);
        Replay replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
        ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, TARGET_PLAYER);
        List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, TARGET_PLAYER);
        Race race = TrackerIntentExtractor.resolvePlayerRace(replay, TARGET_PLAYER);

        System.out.println("\n=== Hellion Tick Trace: " + TARGET_REPLAY + " player " + TARGET_PLAYER + " ===\n");

        // Print Hellion intent details
        List<TimedIntent> hellionIntents = intents.stream()
            .filter(ti -> ti.intent() instanceof TrainIntent t && t.unitType() == UnitType.HELLION)
            .toList();
        long fiveMinLoops = (long) (5 * 60 * SC2Data.GAME_LOOPS_PER_SECOND);
        System.out.printf("Total Hellion intents: %d (before 5-min: %d)%n",
            hellionIntents.size(),
            hellionIntents.stream().filter(ti -> ti.loop() < fiveMinLoops).count());
        System.out.println("Hellion intent commandLoops:");
        for (TimedIntent ti : hellionIntents) {
            TrainIntent t = (TrainIntent) ti.intent();
            int trainTime = SC2Data.trainTimeInLoops(t.unitType());
            long bornLoop = ti.loop() + trainTime;
            int arrivalTick = (int) (ti.loop() / SC2Data.LOOPS_PER_TICK);
            System.out.printf("  commandLoop=%d  bornLoop=%d  arrivalTick~%d  tag=%s%n",
                ti.loop(), bornLoop, arrivalTick, t.buildingTag());
        }

        // Run harness
        DivergenceReport report = ReplayValidationHarness.run(groundTruth, intents, TICK_LIMIT, race);

        // Per-tick trace around 5-min
        int checkpoint5 = 5 * TICKS_PER_MINUTE - 1;
        System.out.printf("%n--- Per-tick Hellion counts (tick 150 to %d) ---%n", checkpoint5 + 20);
        System.out.println("tick | GT_HELLION | EM_HELLION | GT_HELLBAT | EM_HELLBAT | note");
        int prevEmHellion = 0;
        int prevGtHellion = 0;
        boolean emEverExceeded = false;
        for (int tick = 150; tick <= Math.min(checkpoint5 + 20, report.ticks().size() - 1); tick++) {
            var snap = report.ticks().get(tick);
            int gtHellion = snap.groundTruthUnitsByType().getOrDefault(UnitType.HELLION, 0);
            int emHellion = snap.emulatedUnitsByType().getOrDefault(UnitType.HELLION, 0);
            int gtHellbat = snap.groundTruthUnitsByType().getOrDefault(UnitType.HELLBAT, 0);
            int emHellbat = snap.emulatedUnitsByType().getOrDefault(UnitType.HELLBAT, 0);

            String note = "";
            if (emHellion != prevEmHellion) note += " EM_CHANGE(" + prevEmHellion + "→" + emHellion + ")";
            if (gtHellion != prevGtHellion) note += " GT_CHANGE(" + prevGtHellion + "→" + gtHellion + ")";
            if (emHellion > gtHellion) { note += " EM>GT!"; emEverExceeded = true; }

            if (gtHellion != prevGtHellion || emHellion != prevEmHellion
                || tick == checkpoint5 || tick >= 200 && tick <= 215
                || gtHellbat > 0 || emHellbat > 0) {
                System.out.printf("%4d | %10d | %10d | %10d | %10d |%s%n",
                    tick, gtHellion, emHellion, gtHellbat, emHellbat, note);
            }
            prevEmHellion = emHellion;
            prevGtHellion = gtHellion;
        }
        System.out.printf("%nEm ever exceeded GT Hellion count: %s%n", emEverExceeded);

        // Full unit breakdown at 5-min checkpoint
        if (checkpoint5 < report.ticks().size()) {
            var snap5 = report.ticks().get(checkpoint5);
            System.out.println("\n--- All Em units at 5-min checkpoint (tick " + checkpoint5 + ") ---");
            Map<UnitType, Integer> emUnits = new TreeMap<>(snap5.emulatedUnitsByType());
            for (var entry : emUnits.entrySet()) {
                System.out.printf("  %s: %d%n", entry.getKey(), entry.getValue());
            }
            System.out.println("\n--- All GT units at 5-min checkpoint ---");
            Map<UnitType, Integer> gtUnits = new TreeMap<>(snap5.groundTruthUnitsByType());
            for (var entry : gtUnits.entrySet()) {
                System.out.printf("  %s: %d%n", entry.getKey(), entry.getValue());
            }
        }

        // Also check if there's a HELLBAT type
        System.out.println("\n--- Checking for HELLBAT across all ticks ---");
        boolean foundHellbat = false;
        for (int tick = 0; tick < report.ticks().size(); tick++) {
            var snap = report.ticks().get(tick);
            int gtHb = snap.groundTruthUnitsByType().getOrDefault(UnitType.HELLBAT, 0);
            int emHb = snap.emulatedUnitsByType().getOrDefault(UnitType.HELLBAT, 0);
            if (gtHb > 0 || emHb > 0) {
                System.out.printf("  tick %d: GT_HELLBAT=%d, EM_HELLBAT=%d%n", tick, gtHb, emHb);
                foundHellbat = true;
            }
        }
        if (!foundHellbat) System.out.println("  No HELLBAT found in any tick (GT or Em)");
    }
}
