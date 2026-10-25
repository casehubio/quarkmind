package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.mock.Sc2ReplayShared;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerIntentExtractorIntentTest {

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
    void extractsTrainIntentsFromUnitBorn() throws Exception {
        Path replay = Files.list(ORACLE_DIR)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted().findFirst().orElseThrow();

        List<TimedIntent> intents = TrackerIntentExtractor.extract(replay, 1);

        assertFalse(intents.isEmpty(), "Should produce intents from tracker events");

        long trainCount = intents.stream()
            .filter(ti -> ti.intent() instanceof TrainIntent).count();
        assertTrue(trainCount > 0, "Should have TrainIntents from UnitBorn events");

        for (int i = 1; i < intents.size(); i++) {
            assertTrue(intents.get(i).loop() >= intents.get(i - 1).loop(),
                "Intents must be sorted by loop");
        }

        intents.stream()
            .filter(ti -> ti.intent() instanceof TrainIntent)
            .forEach(ti -> assertTrue(ti.loop() >= 0,
                "Back-calculated loop must be non-negative: " + ti));
    }

    @Test
    @EnabledIf("oracleExists")
    void extractsBuildIntentsFromUnitInitDone() throws Exception {
        Path replay = Files.list(ORACLE_DIR)
                           .filter(p -> p.toString().endsWith(".SC2Replay"))
                           .sorted().findFirst().orElseThrow();

        List<TimedIntent> intents = TrackerIntentExtractor.extract(replay, 1);

        long buildCount = intents.stream()
                                 .filter(ti -> ti.intent() instanceof BuildIntent).count();
        assertEquals(0, buildCount,
                     "Tracker path should NOT produce BuildIntents — buildings are synced from ground truth by the harness");
    }

    @Test
    @EnabledIf("oracleExists")
    void extractsResearchIntentsFromUpgradeEvents() throws Exception {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                            .sorted().limit(10).toList();
        }

        long totalResearch = 0;
        for (Path replay : replays) {
            for (int pid = 1; pid <= 2; pid++) {
                try {
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replay, pid);
                    totalResearch += intents.stream()
                                            .filter(ti -> ti.intent() instanceof ResearchIntent).count();
                } catch (Exception e) { /* skip unparseable */ }
            }
        }

        assertTrue(totalResearch > 0,
                   "Should have ResearchIntents from Upgrade tracker events across 10 replays");
    }

    @Test
    @EnabledIf("oracleExists")
    void trackerIntentsCoverMoreThanCmdEvents() throws Exception {
        Path replay = Files.list(ORACLE_DIR)
                           .filter(p -> p.toString().endsWith(".SC2Replay"))
                           .sorted().findFirst().orElseThrow();

        List<TimedIntent> trackerIntents = TrackerIntentExtractor.extract(replay, 1);
        List<TimedIntent> cmdIntents     = ReplayCommandExtractor.extract(replay, 1).intents();

        long trackerTrainCount = trackerIntents.stream()
                                               .filter(ti -> ti.intent() instanceof TrainIntent).count();
        long cmdTrainCount = cmdIntents.stream()
                                       .filter(ti -> ti.intent() instanceof TrainIntent).count();

        System.out.printf("Tracker TrainIntents: %d, CmdEvent TrainIntents: %d (%.1f%% more)%n",
                          trackerTrainCount, cmdTrainCount,
                          cmdTrainCount > 0 ? (trackerTrainCount - cmdTrainCount) * 100.0 / cmdTrainCount : 100.0);

        assertTrue(trackerTrainCount >= cmdTrainCount,
                   "Tracker path should produce at least as many TrainIntents as CmdEvent path");
    }

    @Test
    @EnabledIf("oracleExists")
    void trainIntentsUseBuildingTagsFromGameEvents() throws Exception {
        Path replay = Files.list(ORACLE_DIR)
                           .filter(p -> p.toString().endsWith(".SC2Replay"))
                           .sorted().findFirst().orElseThrow();

        Replay parsed = RepParserEngine.parseReplay(replay,
                                                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS, RepContent.GAME_EVENTS));

        Set<String> buildingTags = new HashSet<>();
        for (Event raw : parsed.trackerEvents.getEvents()) {
            if (raw.getId() == ITrackerEvents.ID_UNIT_INIT && raw instanceof IBaseUnitEvent ue) {
                Integer pid = ue.getControlPlayerId();
                if (pid != null && pid == 1) {
                    String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                    if (Sc2ReplayShared.BUILDING_NAMES.contains(typeName)) {
                        buildingTags.add("r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle());
                    }
                }
            }
        }

        List<TimedIntent> intents = TrackerIntentExtractor.extractFromReplay(parsed, 1);

        long matchingBuildingTag = intents.stream()
                                          .filter(ti -> ti.intent() instanceof TrainIntent)
                                          .map(ti -> ((TrainIntent) ti.intent()).buildingTag())
                                          .filter(buildingTags::contains)
                                          .count();

        long totalTrain = intents.stream()
                                 .filter(ti -> ti.intent() instanceof TrainIntent).count();

        System.out.printf("TrainIntents with building tags: %d / %d (%.1f%%)%n",
                          matchingBuildingTag, totalTrain,
                          totalTrain > 0 ? matchingBuildingTag * 100.0 / totalTrain : 0);

        // Some intents should have building tags matching actual buildings.
        // Not all will match — when the nearest CmdEvent had army units selected,
        // the tag won't match a building and EmulatedGame will auto-resolve.
        assertTrue(matchingBuildingTag > 0,
                   "With game events, some TrainIntents should have building tags matching actual buildings");
    }

    @Test
    @EnabledIf("oracleExists")
    void buildingIndexPopulatedFromGameEvents() throws Exception {
        Path replay = Files.list(ORACLE_DIR)
                           .filter(p -> p.toString().endsWith(".SC2Replay"))
                           .sorted().findFirst().orElseThrow();

        Replay parsed = RepParserEngine.parseReplay(replay,
                                                    EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS, RepContent.GAME_EVENTS));

        List<TimedIntent> withGameEvents = TrackerIntentExtractor.extractFromReplay(parsed, 1);

        // Strip game events and extract again — intents should differ
        Replay noGameEvents = RepParserEngine.parseReplay(replay,
                                                          EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS));

        List<TimedIntent> withoutGameEvents = TrackerIntentExtractor.extractFromReplay(noGameEvents, 1);

        assertEquals(withGameEvents.size(), withoutGameEvents.size(),
                     "Same number of intents regardless of game events");

        long diffCount = 0;
        for (int i = 0; i < withGameEvents.size(); i++) {
            if (withGameEvents.get(i).intent() instanceof TrainIntent a
                && withoutGameEvents.get(i).intent() instanceof TrainIntent b) {
                if (!a.buildingTag().equals(b.buildingTag())) {
                    diffCount++;
                }
            }
        }

        System.out.printf("TrainIntents with different building tags (game events vs none): %d / %d%n",
                          diffCount, withGameEvents.stream().filter(ti -> ti.intent() instanceof TrainIntent).count());

        assertTrue(diffCount > 0,
                   "With game events, some TrainIntents should have building tags from selection state (different from tracker-unit tags)");
    }


}
