package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tag("diagnostic")
class ReactorStateAtIntentTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final String TARGET_REPLAY = "3307d878e8b14fa2fc3a8c9ffbc81e19acbd73fb26e628438ce76bcaf7620d65.SC2Replay";
    private static final int TARGET_PLAYER = 1;
    private static final int TICK_LIMIT = (int)(8 * 60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] PROBE_TICKS = {150, 160, 170, 180, 190, 200, 205, 210, 220, 225, 226, 227, 230, 240, 250};

    static boolean replayExists() {
        return Files.exists(ORACLE_DIR.resolve(TARGET_REPLAY));
    }

    @Test
    @EnabledIf("replayExists")
    void traceReactorStateAtIntentTime() {
        Path replayPath = ORACLE_DIR.resolve(TARGET_REPLAY);

        Replay replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS, RepContent.TRACKER_EVENTS, RepContent.GAME_EVENTS));
        ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, TARGET_PLAYER);
        List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, TARGET_PLAYER);
        Race race = TrackerIntentExtractor.resolvePlayerRace(replay, TARGET_PLAYER);

        System.out.println("\n=== Reactor State at Intent Time — Replay 3307d, Player 1 ===\n");

        List<Long> hellionLoops = intents.stream()
            .filter(ti -> ti.intent() instanceof TrainIntent t && t.unitType() == UnitType.HELLION)
            .map(TimedIntent::loop).sorted().toList();
        System.out.println("Hellion intent loops: " + hellionLoops);

        EmulatedGame emulated = new EmulatedGame();
        emulated.setPlayerRaceModel(RaceModelFactory.forRace(race));

        groundTruth.reset();
        emulated.reset();

        Set<String> reactoredTags = groundTruth.reactoredBuildingTags();
        Set<String> injectedTags = new HashSet<>();
        int cursor = 0;
        int probeIdx = 0;

        for (int tick = 0; tick < TICK_LIMIT && !groundTruth.isComplete(); tick++) {
            long windowEnd = (long)(tick + 1) * SC2Data.LOOPS_PER_TICK;

            GameState gtBefore = groundTruth.snapshot();
            emulated.setMiningProbesPerBase(ReplayValidationHarness.countWorkersPerBase(race, gtBefore));

            emulated.tick();
            groundTruth.tick();

            GameState gt = groundTruth.snapshot();

            // syncBuildings inline
            for (Building gtBuilding : gt.myBuildings()) {
                if (!injectedTags.contains(gtBuilding.tag())) {
                    emulated.injectReplayBuilding(gtBuilding);
                    injectedTags.add(gtBuilding.tag());
                } else {
                    emulated.syncReplayBuilding(gtBuilding);
                }
                if (gtBuilding.isComplete() && reactoredTags.contains(gtBuilding.tag())) {
                    emulated.markBuildingReactored(gtBuilding.tag());
                }
            }

            if (gt.supply() > 0) {
                emulated.setSupplyCapForHarness(gt.supply());
            }
            emulated.setVespeneForHarness(gtBefore.vespene());

            emulated.processDeferredTrainIntents();
            emulated.processDeferredResearchIntents();

            // Print state at probe ticks BEFORE applying intents
            if (probeIdx < PROBE_TICKS.length && tick == PROBE_TICKS[probeIdx]) {
                printState(tick, emulated, groundTruth, reactoredTags);
                probeIdx++;
            }

            // Apply intents
            while (cursor < intents.size() && intents.get(cursor).loop() < windowEnd) {
                TimedIntent ti = intents.get(cursor);
                if (ti.intent() instanceof TrainIntent t && t.unitType() == UnitType.HELLION) {
                    System.out.printf("  >> APPLYING Hellion intent at loop %d (tick %d, windowEnd %d)%n", ti.loop(), tick, windowEnd);
                    // Check Factory state right before applying
                    GameState emSnap = emulated.snapshot();
                    List<Building> factories = emSnap.myBuildings().stream()
                        .filter(b -> b.type() == BuildingType.FACTORY && b.isComplete()).toList();
                    System.out.printf("     Complete Factories available: %d%n", factories.size());
                    for (Building f : factories) {
                        System.out.printf("       %s reactored=%b%n", f.tag(), emulated.isReactored(f.tag()));
                    }
                    if (factories.isEmpty()) {
                        List<Building> allFactories = emSnap.myBuildings().stream()
                            .filter(b -> b.type() == BuildingType.FACTORY).toList();
                        System.out.printf("     Incomplete Factories: %d%n", allFactories.size());
                        for (Building f : allFactories) {
                            System.out.printf("       %s complete=%b%n", f.tag(), f.isComplete());
                        }
                        // Check town halls as fallback
                        List<Building> townHalls = emSnap.myBuildings().stream()
                            .filter(b -> b.isComplete() && (b.type() == BuildingType.COMMAND_CENTER
                                || b.type() == BuildingType.ORBITAL_COMMAND
                                || b.type() == BuildingType.PLANETARY_FORTRESS)).toList();
                        System.out.printf("     Town halls (fallback): %d%n", townHalls.size());
                        for (Building th : townHalls) {
                            System.out.printf("       %s type=%s%n", th.tag(), th.type());
                        }
                    }
                }
                emulated.applyIntent(intents.get(cursor));
                cursor++;
            }

            // Count Hellions in Em snapshot after applying
            GameState emAfter = emulated.snapshot();
            int hellionCount = (int) emAfter.myUnits().stream().filter(u -> u.type() == UnitType.HELLION).count();
            if (tick >= 200 && tick <= 280 && hellionCount > 0) {
                int gtHellions = (int) gt.myUnits().stream().filter(u -> u.type() == UnitType.HELLION).count();
                if (tick == 205 || tick == 226 || tick == 247 || tick == 248 || tick == 274 || tick == 275 || tick == 276
                    || (tick >= 200 && tick <= 210)) {
                    System.out.printf("  tick %d: Em Hellions=%d, GT Hellions=%d%n", tick, hellionCount, gtHellions);
                }
            }

            if (tick > 310) break;
        }

        System.out.println("\n=== GT reactored tags (final): " + reactoredTags + " ===");
        System.out.println("=== Em Factories (final) ===");
        GameState finalEm = emulated.snapshot();
        finalEm.myBuildings().stream()
            .filter(b -> b.type() == BuildingType.FACTORY)
            .forEach(b -> System.out.printf("  %s complete=%b reactored=%b%n", b.tag(), b.isComplete(), emulated.isReactored(b.tag())));
    }

    private void printState(int tick, EmulatedGame emulated, ReplaySimulatedGame gt, Set<String> reactoredTags) {
        System.out.printf("--- tick %d ---%n", tick);
        GameState emSnap = emulated.snapshot();

        // All Em buildings
        System.out.println("  Em buildings:");
        for (Building b : emSnap.myBuildings()) {
            boolean reactored = emulated.isReactored(b.tag());
            if (b.type() == BuildingType.FACTORY || b.type() == BuildingType.BARRACKS
                || b.type() == BuildingType.STARPORT || b.type() == BuildingType.COMMAND_CENTER
                || b.type() == BuildingType.ORBITAL_COMMAND) {
                System.out.printf("    %s type=%-20s complete=%-5b reactored=%b%n",
                    b.tag(), b.type(), b.isComplete(), reactored);
            }
        }

        // GT reactored tags at this point
        System.out.println("  GT reactoredBuildingTags: " + reactoredTags);

        // Completed Factories specifically
        List<Building> factories = emSnap.myBuildings().stream()
            .filter(b -> b.type() == BuildingType.FACTORY && b.isComplete()).toList();
        System.out.printf("  Completed Factories: %d%n", factories.size());
        for (Building f : factories) {
            System.out.printf("    %s reactored=%b%n", f.tag(), emulated.isReactored(f.tag()));
        }
    }
}
