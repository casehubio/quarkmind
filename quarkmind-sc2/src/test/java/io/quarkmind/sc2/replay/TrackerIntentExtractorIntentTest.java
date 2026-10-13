package io.quarkmind.sc2.replay;

import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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


}
