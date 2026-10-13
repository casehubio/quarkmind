package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tag("diagnostic")
class ReactorTagTraceDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;

    static boolean replayExists() {
        return Files.exists(ORACLE_DIR.resolve(
            "562c544b6dbd46ab28c35b5f0bb22b8efc4c3d4b260d07bf1f9e711d76a7b6f5.SC2Replay"));
    }

    @Test
    @EnabledIf("replayExists")
    void traceReactorTagPropagation() throws IOException {
        Path replayPath = ORACLE_DIR.resolve(
            "562c544b6dbd46ab28c35b5f0bb22b8efc4c3d4b260d07bf1f9e711d76a7b6f5.SC2Replay");
        int playerId = 2;

        ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
        List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
        Replay replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
        Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));

        groundTruth.reset();
        emulated.reset();

        Set<String> reactoredTags = groundTruth.reactoredBuildingTags();
        Set<String> injectedTags = new HashSet<>();
        int cursor = 0;
        boolean seenTarget = false;

        System.out.println("\n=== Reactor Tag Trace: 562c5... Player 2 ===\n");
        System.out.printf("reactoredTags ref identity: %s (size=%d after reset)%n",
            System.identityHashCode(reactoredTags), reactoredTags.size());

        for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
            long windowEnd = (long) (tick + 1) * SC2Data.LOOPS_PER_TICK;

            GameState gtBefore = groundTruth.snapshot();
            emulated.setMiningProbesPerBase(ReplayValidationHarness.countWorkersPerBase(race, gtBefore));

            int reactoredBefore = reactoredTags.size();

            emulated.tick();
            groundTruth.tick();

            int reactoredAfter = reactoredTags.size();
            if (reactoredAfter > reactoredBefore) {
                System.out.printf("  [tick %d] reactoredTags grew %d->%d: %s%n",
                    tick, reactoredBefore, reactoredAfter, reactoredTags);
            }

            GameState gt = groundTruth.snapshot();

            // Check if r-236-1 appears in GT buildings
            for (Building b : gt.myBuildings()) {
                if (b.tag().equals("r-236-1") && !seenTarget) {
                    System.out.printf("  [tick %d] FOUND r-236-1 in GT myBuildings: type=%s complete=%s%n",
                        tick, b.type(), b.isComplete());
                    seenTarget = true;
                }
            }

            // Replicate syncBuildings with logging
            for (Building gtBuilding : gt.myBuildings()) {
                if (!injectedTags.contains(gtBuilding.tag())) {
                    emulated.injectReplayBuilding(gtBuilding);
                    injectedTags.add(gtBuilding.tag());
                    if (gtBuilding.type() == BuildingType.FACTORY
                        || gtBuilding.type() == BuildingType.BARRACKS
                        || gtBuilding.type() == BuildingType.STARPORT) {
                        System.out.printf("  [tick %d] INJECT %s tag=%s complete=%s%n",
                            tick, gtBuilding.type(), gtBuilding.tag(), gtBuilding.isComplete());
                    }
                } else {
                    emulated.syncReplayBuilding(gtBuilding);
                }
                if (gtBuilding.isComplete() && reactoredTags.contains(gtBuilding.tag())) {
                    emulated.markBuildingReactored(gtBuilding.tag());
                    // Only log once per tag
                    if (!emulated.isReactored(gtBuilding.tag())) {
                        // Already marked, skip
                    } else {
                        // Check: was it JUST marked?
                        System.out.printf("  [tick %d] REACTOR MARK tag=%s type=%s%n",
                            tick, gtBuilding.tag(), gtBuilding.type());
                    }
                }
            }

            if (gt.supply() > 0) {
                emulated.setSupplyCapForHarness(gt.supply());
            }
            emulated.setVespeneForHarness(gtBefore.vespene());

            emulated.processDeferredTrainIntents();
            emulated.processDeferredResearchIntents();

            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                emulated.applyIntent(intents.get(cursor));
                cursor++;
            }

            // Periodic status: every minute
            if (tick > 0 && tick % TICKS_PER_MINUTE == 0) {
                int minute = tick / TICKS_PER_MINUTE;
                System.out.printf("  --- %d-min status ---%n", minute);
                System.out.printf("    reactoredTags: %s%n", reactoredTags);
                GameState emSnap = emulated.snapshot();
                List<Building> factories = emSnap.myBuildings().stream()
                    .filter(b -> b.type() == BuildingType.FACTORY).toList();
                for (Building f : factories) {
                    System.out.printf("    Em Factory: tag=%s complete=%s reactored=%s%n",
                        f.tag(), f.isComplete(), emulated.isReactored(f.tag()));
                }
                // Check for r-236-1 in ANY building type
                emSnap.myBuildings().stream()
                    .filter(b -> b.tag().equals("r-236-1"))
                    .forEach(b -> System.out.printf("    *** r-236-1 found as type=%s complete=%s%n",
                        b.type(), b.isComplete()));
                if (emSnap.myBuildings().stream().noneMatch(b -> b.tag().equals("r-236-1"))) {
                    System.out.printf("    *** r-236-1 NOT in Em buildings at all%n");
                }
            }
        }

        System.out.println("\n  --- Final state ---");
        System.out.printf("  reactoredTags: %s%n", reactoredTags);
        GameState emFinal = emulated.snapshot();
        System.out.printf("  ALL Em buildings (%d):%n", emFinal.myBuildings().size());
        for (Building b : emFinal.myBuildings()) {
            System.out.printf("    tag=%s type=%s complete=%s reactored=%s%n",
                b.tag(), b.type(), b.isComplete(), emulated.isReactored(b.tag()));
        }
    }
}
