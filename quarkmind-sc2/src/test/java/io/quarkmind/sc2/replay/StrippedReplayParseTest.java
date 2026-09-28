package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import io.quarkmind.sc2.intent.TimedIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrippedReplayParseTest {

    private static final Path LADDER_493 = Path.of(
        "quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    static boolean ladderReplaysExist() {
        Path dir = Path.of(System.getProperty("user.dir")).getParent().resolve(LADDER_493);
        return Files.isDirectory(dir);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void canParseGameEventsFromStrippedReplay() throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).getParent().resolve(LADDER_493);
        Path replay = Files.list(dir)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted()
            .findFirst()
            .orElseThrow();

        List<Event> events = GameEventStream.events(replay);
        assertThat(events).as("Stripped replay must have game events").isNotEmpty();

        int cmdCount = 0;
        Map<Integer, Integer> abilLinks = new HashMap<>();
        for (Event e : events) {
            if (e instanceof CmdEvent cmd) {
                cmdCount++;
                if (cmd.getAbilLink() != null) {
                    abilLinks.merge(cmd.getAbilLink(), 1, Integer::sum);
                }
            }
        }

        System.out.println("Stripped replay: " + replay.getFileName());
        System.out.println("Total events: " + events.size());
        System.out.println("CmdEvents: " + cmdCount);
        System.out.println("Distinct abilLinks: " + abilLinks.size());
        abilLinks.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(15)
            .forEach(e -> System.out.println("  abilLink=" + e.getKey() + " count=" + e.getValue()));

        assertThat(cmdCount).as("Must have command events").isGreaterThan(0);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void replayCommandExtractorWorksOnStrippedReplay() throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).getParent().resolve(LADDER_493);
        Path replay = Files.list(dir)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted()
            .findFirst()
            .orElseThrow();

        ReplayCommandStream stream = ReplayCommandExtractor.extract(replay, 1);

        System.out.println("ReplayCommandExtractor on stripped replay:");
        System.out.println("  Orders: " + stream.movementOrders().size());
        System.out.println("  Intents: " + stream.intents().size());
        for (TimedIntent ti : stream.intents()) {
            System.out.println("    loop=" + ti.loop() + " intent=" + ti.intent());
        }

        assertThat(stream.movementOrders().size() + stream.intents().size())
            .as("Must extract at least some commands").isGreaterThan(0);
    }
}
