package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class UnmappedAbilCmdIndexDiagnosticTest {

    private static final Path LADDER_493 = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3/replays");

    private static final Map<Integer, String> ABIL_NAMES = Map.of(
        159, "Barracks", 160, "Factory", 161, "Starport",
        172, "Gateway", 173, "Stargate", 174, "Robotics",
        193, "Larva"
    );

    private static final Map<Integer, Set<Integer>> KNOWN_INDICES = Map.of(
        159, Set.of(0, 1, 3),
        160, Set.of(1, 5, 6, 7, 24),
        161, Set.of(0, 1, 2, 3, 4, 6),
        172, Set.of(0, 1, 5),
        173, Set.of(0, 2, 8),
        174, Set.of(0, 1, 2, 3),
        193, Set.of(0, 1, 2, 3, 4, 6, 9, 10, 11, 14)
    );

    static boolean ladderReplaysExist() {
        return Files.isDirectory(LADDER_493);
    }

    @Test
    @EnabledIf("ladderReplaysExist")
    void findUnmappedAbilCmdIndices() throws Exception {
        // (abilLink, idx) -> count of CmdEvents using this unmapped index
        Map<String, Integer> unmapped = new TreeMap<>();

        int replaysScanned = 0;
        try (var stream = Files.list(LADDER_493)) {
            for (Path rp : stream.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().limit(500).toList()) {
                Replay replay;
                try {
                    replay = RepParserEngine.parseReplay(rp, EnumSet.of(RepContent.GAME_EVENTS));
                } catch (Exception e) { continue; }
                if (replay == null || replay.gameEvents == null) continue;
                replaysScanned++;

                for (Event raw : replay.gameEvents.getEvents()) {
                    if (!(raw instanceof CmdEvent cmd)) continue;
                    Integer abilLink = cmd.getAbilLink();
                    if (abilLink == null) continue;
                    Set<Integer> known = KNOWN_INDICES.get(abilLink);
                    if (known == null) continue;

                    int idx = cmd.getAbilCmdIndex() != null ? cmd.getAbilCmdIndex() : 0;
                    boolean hasTP = cmd.getTargetPoint() != null;
                    if (!known.contains(idx)) {
                        String key = String.format("abil=%d(%s) idx=%d hasTP=%s",
                            abilLink, ABIL_NAMES.get(abilLink), idx, hasTP);
                        unmapped.merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        System.out.printf("%n=== Unmapped abilCmdIndex Values (%d replays scanned) ===%n%n", replaysScanned);
        System.out.printf("%-50s  %s%n", "Key", "Count");
        System.out.println("-".repeat(60));
        unmapped.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> System.out.printf("%-50s  %d%n", e.getKey(), e.getValue()));

        assertThat(replaysScanned).isGreaterThan(0);
    }
}
