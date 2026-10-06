package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("diagnostic")
class CmdEventGapDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final Path AIARENA_DIR = Path.of(
        "replays/aiarena_protoss");

    private static final Set<String> AUTO_SPAWN_UNITS = Set.of(
        "Larva", "Interceptor", "Broodling", "BroodlingEscort",
        "LocustMP", "LocustMPFlying", "InfestedTerran",
        "AutoTurret", "PointDefenseDrone", "NydusCanal",
        "AdeptPhaseShift", "DisruptorPhased", "KD8Charge",
        "InfestedTerransEgg", "LocustMPPrecursor",
        "Changeling", "ChangelingMarine", "ChangelingZealot",
        "ChangelingZergling", "ChangelingZerglingWings",
        "ParasiticBombDummy", "ParasiticBombRelayDummy"
    );

    private static final Set<Integer> PRODUCTION_ABIL_LINKS = Set.of(
        155, 159, 160, 161, 172, 173, 174, 175, 193, 184, 186, 214, 90
    );

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var s = Files.list(ORACLE_DIR)) {
            return s.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    static boolean aiarenaExists() {
        return Files.isDirectory(AIARENA_DIR);
    }

    @Test
    @EnabledIf("aiarenaExists")
    void compareFullVsOracleReplays() throws Exception {
        System.out.println("\n=== Full Replay (AI Arena) CmdEvent Gap ===\n");
        System.out.printf("%-40s  %6s %6s %6s  %s%n", "Replay", "Oracle", "Mapped", "Prod", "Ratio%");
        System.out.println("-".repeat(80));

        List<Path> aiarenaReplays;
        try (var s = Files.list(AIARENA_DIR)) {
            aiarenaReplays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
        }

        int totalOracle = 0;
        int totalMapped = 0;

        for (Path replayPath : aiarenaReplays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath,
                    EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.gameEvents == null
                || replay.trackerEvents == null || replay.details == null) continue;

            Player[] players = replay.details.getPlayerList();
            if (players.length < 2) continue;

            int oracle = countOracleUnits(replay);
            int[] stats = analyzeReplay(replay, players, false);

            totalOracle += oracle;
            totalMapped += stats[0];

            double ratio = oracle > 0 ? 100.0 * stats[0] / oracle : 0;
            String name = replayPath.getFileName().toString();
            System.out.printf("%-40s  %6d %6d %6d  %5.1f%%%n",
                name.substring(0, Math.min(40, name.length())),
                oracle, stats[0], stats[1], ratio);
        }

        System.out.println("-".repeat(80));
        System.out.printf("%-40s  %6d %6d        %5.1f%%%n",
            "AI Arena TOTAL", totalOracle, totalMapped,
            totalOracle > 0 ? 100.0 * totalMapped / totalOracle : 0);

        if (oracleExists()) {
            System.out.println("\n=== Oracle (Restored Ladder) CmdEvent Gap ===\n");
            List<Path> oracleReplays;
            try (var s = Files.list(ORACLE_DIR)) {
                oracleReplays = s.filter(p -> p.toString().endsWith(".SC2Replay")).sorted().toList();
            }

            int oracleTotal = 0;
            int oracleMapped = 0;

            for (Path replayPath : oracleReplays) {
                Replay replay;
                try {
                    replay = RepParserEngine.parseReplay(replayPath,
                        EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
                } catch (Exception e) { continue; }
                if (replay == null || replay.gameEvents == null
                    || replay.trackerEvents == null || replay.details == null) continue;

                Player[] players = replay.details.getPlayerList();
                if (players.length < 2) continue;

                int oracle = countOracleUnits(replay);
                int[] stats = analyzeReplay(replay, players, true);

                oracleTotal += oracle;
                oracleMapped += stats[0];
            }

            System.out.printf("%-40s  %6d %6d        %5.1f%%%n",
                "Oracle TOTAL", oracleTotal, oracleMapped,
                oracleTotal > 0 ? 100.0 * oracleMapped / oracleTotal : 0);
        }

        System.out.printf("%n=== Comparison ===%n");
        System.out.printf("AI Arena (full replays):   %5.1f%% CmdEvent detection%n",
            totalOracle > 0 ? 100.0 * totalMapped / totalOracle : 0);
        System.out.println("Oracle (restored ladder):  ~43% (from prior runs)");
        System.out.println("\nIf AI Arena ~100%% and Oracle ~43%%: oracle replays have incomplete game events");
        System.out.println("If both ~43%%: fundamental SC2 protocol limitation");

        assertThat(totalMapped).isGreaterThan(0);
    }

    private int[] analyzeReplay(Replay replay, Player[] players, boolean humanReplay) {
        int mappedTrains = 0;
        int productionCmds = 0;

        Event[] gameEvents = replay.gameEvents.getEvents();
        int[] actualUserIds = detectUserIds(gameEvents);
        AbilityProfile profile = AbilityProfile.resolve(
            replay.header != null && replay.header.baseBuild != null ? replay.header.baseBuild : 75689);

        for (int playerId = 1; playerId <= 2; playerId++) {
            int pi = playerId - 1;
            Race race = players[pi].getRace();
            int userId = actualUserIds[pi];
            AbilityMapping mapping = new AbilityMapping(userId + 1, humanReplay, race, profile);

            for (Event raw : gameEvents) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    mapping.onSelection(sel);
                } else if (raw instanceof CmdEvent cmd) {
                    if (cmd.getUserId() != userId) continue;

                    Integer abilLink = cmd.getAbilLink();
                    if (abilLink != null && PRODUCTION_ABIL_LINKS.contains(abilLink)) {
                        productionCmds++;
                    }

                    for (ReplayCommand rc : mapping.process(cmd)) {
                        if (rc instanceof ReplayCommand.IntentCommand ic
                            && ic.intent().intent() instanceof TrainIntent) {
                            mappedTrains++;
                        }
                    }
                }
            }
        }

        return new int[]{mappedTrains, productionCmds};
    }

    private int countOracleUnits(Replay replay) {
        int count = 0;
        for (Event raw : replay.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UNIT_BORN) continue;
            IBaseUnitEvent born = (IBaseUnitEvent) raw;
            if (born.getControlPlayerId() == null || born.getControlPlayerId() == 0) continue;
            if (born.getLoop() == 0) continue;
            String typeName = String.valueOf(born.getUnitTypeName()).trim();
            if (AUTO_SPAWN_UNITS.contains(typeName)) continue;
            count++;
        }
        return count;
    }

    private static int[] detectUserIds(Event[] gameEvents) {
        Map<Integer, Integer> cmdCounts = new java.util.HashMap<>();
        for (Event e : gameEvents) {
            if (e instanceof CmdEvent cmd && cmd.getUserId() >= 0) {
                cmdCounts.merge(cmd.getUserId(), 1, Integer::sum);
            }
        }
        List<Integer> sorted = cmdCounts.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .toList();
        if (sorted.size() >= 2) {
            int first = Math.min(sorted.get(0), sorted.get(1));
            int second = Math.max(sorted.get(0), sorted.get(1));
            return new int[]{first, second};
        }
        return new int[]{0, 1};
    }
}
