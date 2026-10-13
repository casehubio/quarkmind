package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Race;
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
class ChronoBoostAbilLinkDiscoveryTest {

    private static final Path ORACLE_RESTORED = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");

    private static final Set<Integer> KNOWN_ABIL_LINKS = Set.of(
        42, 45, 170, 172, 173, 174, 175,  // Protoss train/move
        236, 237, 547, 180, 181, 182, 177, 608, 69, 71,  // Protoss upgrades
        267, 214  // Archon merge, warp-in
    );

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_RESTORED)) return false;
        try (var s = Files.list(ORACLE_RESTORED)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void discoverChronoBoostAbilLink() throws Exception {
        Map<String, Integer> targetedAbilLinks = new TreeMap<>();
        Map<String, Integer> untargetedAbilLinks = new TreeMap<>();
        int protossReplays = 0;

        try (var paths = Files.list(ORACLE_RESTORED)) {
            for (Path replayPath : paths.filter(p -> p.toString().endsWith(".SC2Replay")).toList()) {
                Replay replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.DETAILS));
                if (replay == null || replay.details == null || replay.gameEvents == null) continue;

                var players = replay.details.getPlayerList();
                for (int pid = 0; pid < players.length; pid++) {
                    if (players[pid].getRace() != Race.PROTOSS) continue;
                    protossReplays++;
                    int userId = pid;

                    for (Event ge : replay.gameEvents.getEvents()) {
                        if (!(ge instanceof CmdEvent cmd)) continue;
                        Integer cmdPlayer = cmd.getUserId();
                        if (cmdPlayer == null || cmdPlayer != userId) continue;

                        Integer abl = cmd.getAbilLink();
                        if (abl == null || abl <= 0) continue;
                        if (KNOWN_ABIL_LINKS.contains(abl)) continue;

                        Integer idx = cmd.getAbilCmdIndex();
                        int abilCmdIdx = idx != null ? idx : 0;
                        String key = abl + "/" + abilCmdIdx;

                        if (cmd.getTargetUnit() != null && cmd.getTargetUnit().getTag() != null) {
                            targetedAbilLinks.merge(key, 1, Integer::sum);
                        } else {
                            untargetedAbilLinks.merge(key, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        System.out.println("=== Chrono Boost AbilLink Discovery ===");
        System.out.println("Protoss player-replays scanned: " + protossReplays);
        System.out.println();
        System.out.println("--- Unit-targeted CmdEvents (likely Chrono Boost) ---");
        targetedAbilLinks.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> System.out.printf("  abilLink/idx=%s  count=%d%n", e.getKey(), e.getValue()));

        System.out.println();
        System.out.println("--- Non-targeted unknown abilLinks ---");
        untargetedAbilLinks.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> System.out.printf("  abilLink/idx=%s  count=%d%n", e.getKey(), e.getValue()));

        assertThat(targetedAbilLinks).as("Must find Protoss unit-targeted abilities").isNotEmpty();
    }
}
