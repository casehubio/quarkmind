package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.Delta;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Scans replays and prints all observed (abilLink, abilCmdIndex) tuples with counts.
 * No assertions — output populates AbilityMapping's static table.
 * Covers Protoss (userId=0) and Zerg (userId=1) from PvZ aiarena replays.
 * Terran coverage requires .SC2Replay files — see issue #140.
 */
class AbilityDiscoveryTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("replayFiles")
    void discoverAbilityIds(Path replayPath) {
        List<Event> events = GameEventStream.events(replayPath);
        Map<Integer, List<String>> selections = new HashMap<>();
        Map<String, Long> counts = new TreeMap<>();

        for (Event raw : events) {
            if (raw instanceof SelectionDeltaEvent sel) {
                Delta delta = sel.getDelta();
                if (delta == null || delta.getAddUnitTags() == null) continue;
                List<String> tags = Arrays.stream(delta.getAddUnitTags())
                    .filter(Objects::nonNull)
                    .map(GameEventStream::decodeTag)
                    .toList();
                selections.put(sel.getUserId(), tags);
            } else if (raw instanceof CmdEvent cmd) {
                Integer abilLink = cmd.getAbilLink();
                if (abilLink == null) continue;
                int selSize = selections.getOrDefault(cmd.getUserId(), List.of()).size();
                String key = String.format(
                    "userId=%d  abilLink=%5d  abilCmdIndex=%d  selSize=%2d  hasTP=%-5s  hasTU=%-5s",
                    cmd.getUserId(), abilLink,
                    Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0),
                    selSize,
                    cmd.getTargetPoint() != null,
                    cmd.getTargetUnit() != null);
                counts.merge(key, 1L, Long::sum);
            }
        }

        System.out.println("\n=== Ability IDs: " + replayPath.getFileName() + " ===");
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> System.out.printf("  count=%5d  %s%n", e.getValue(), e.getKey()));
        System.out.println();
    }

    static Stream<Path> replayFiles() {
        return Stream.of(
            Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay"),
            Path.of("replays/aiarena_protoss/ArgoBot_4721229.SC2Replay")
        );
    }
}
