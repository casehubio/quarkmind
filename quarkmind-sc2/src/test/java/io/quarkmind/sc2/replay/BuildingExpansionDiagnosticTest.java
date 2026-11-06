package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.BuildingBornIntent;
import io.quarkmind.sc2.intent.BuildingDiedIntent;
import io.quarkmind.sc2.intent.MorphIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Tag("diagnostic")
class BuildingExpansionDiagnosticTest {

    private static final Path ORACLE_DIR = Path.of(
        "../quarkmind-classifier/data/replay_packs/blizzard_ladder/4.9.3_oracle/restored");
    private static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    private static final int[] CHECKPOINTS_MIN = {1, 3, 5, 7};
    private static final int TICK_LIMIT = TICKS_PER_MINUTE * 8;
    private static final Set<BuildingType> TOWN_HALLS = EnumSet.of(
        BuildingType.COMMAND_CENTER, BuildingType.HATCHERY, BuildingType.NEXUS);

    static boolean oracleExists() {
        if (!Files.isDirectory(ORACLE_DIR)) return false;
        try (var stream = Files.list(ORACLE_DIR)) {
            return stream.anyMatch(p -> p.toString().endsWith(".SC2Replay"));
        } catch (Exception e) { return false; }
    }

    @Test
    @EnabledIf("oracleExists")
    void buildingExpansionDiagnostic() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        System.out.println("\n=== Building Expansion Diagnostic ===\n");

        Map<BuildingType, int[]> aggregateDelta = new EnumMap<>(BuildingType.class);
        for (BuildingType bt : TOWN_HALLS) {
            aggregateDelta.put(bt, new int[CHECKPOINTS_MIN.length * 2]);
        }

        int worstCcDeficit = 0;
        String worstCcReplay = "";
        int worstCcPlayer = 0;
        int worstHatchDeficit = 0;
        String worstHatchReplay = "";
        int worstHatchPlayer = 0;

        int totalRuns = 0;
        int runsWithCcDeficit = 0;
        int runsWithHatchDeficit = 0;

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);

                    List<DivergenceReport.TickSnapshot> snapshots =
                        StandaloneEmulatedGameBaselineTest.runStandalone(
                            groundTruth, intents, TICK_LIMIT, race);

                    long bornCC = intents.stream().filter(ti -> ti.intent() instanceof BuildingBornIntent bb
                        && bb.type() == BuildingType.COMMAND_CENTER).count();
                    long buildCC = intents.stream().filter(ti -> ti.intent() instanceof BuildIntent b
                        && b.buildingType() == BuildingType.COMMAND_CENTER).count();
                    long morphFromCC = intents.stream().filter(ti -> ti.intent() instanceof MorphIntent m
                        && "COMMAND_CENTER".equals(m.sourceName())).count();
                    long diedCC = intents.stream().filter(ti -> ti.intent() instanceof BuildingDiedIntent bd
                        && bd.type() == BuildingType.COMMAND_CENTER).count();

                    long bornHatch = intents.stream().filter(ti -> ti.intent() instanceof BuildingBornIntent bb
                        && bb.type() == BuildingType.HATCHERY).count();
                    long buildHatch = intents.stream().filter(ti -> ti.intent() instanceof BuildIntent b
                        && b.buildingType() == BuildingType.HATCHERY).count();
                    long morphFromHatch = intents.stream().filter(ti -> ti.intent() instanceof MorphIntent m
                        && "HATCHERY".equals(m.sourceName())).count();
                    long diedHatch = intents.stream().filter(ti -> ti.intent() instanceof BuildingDiedIntent bd
                        && bd.type() == BuildingType.HATCHERY).count();

                    boolean hasCcDeficit = false;
                    boolean hasHatchDeficit = false;

                    for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
                        int tickIndex = CHECKPOINTS_MIN[ci] * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= snapshots.size()) continue;
                        var snap = snapshots.get(tickIndex);

                        for (BuildingType bt : TOWN_HALLS) {
                            int gtCount = snap.groundTruthBuildingsByType().getOrDefault(bt, 0);
                            int emCount = snap.emulatedBuildingsByType().getOrDefault(bt, 0);
                            int delta = emCount - gtCount;
                            aggregateDelta.get(bt)[ci * 2] += gtCount;
                            aggregateDelta.get(bt)[ci * 2 + 1] += emCount;

                            if (bt == BuildingType.COMMAND_CENTER && delta < 0) {
                                hasCcDeficit = true;
                                if (-delta > worstCcDeficit) {
                                    worstCcDeficit = -delta;
                                    worstCcReplay = replayPath.getFileName().toString();
                                    worstCcPlayer = playerId;
                                }
                            }
                            if (bt == BuildingType.HATCHERY && delta < 0) {
                                hasHatchDeficit = true;
                                if (-delta > worstHatchDeficit) {
                                    worstHatchDeficit = -delta;
                                    worstHatchReplay = replayPath.getFileName().toString();
                                    worstHatchPlayer = playerId;
                                }
                            }
                        }
                    }

                    if (hasCcDeficit) runsWithCcDeficit++;
                    if (hasHatchDeficit) runsWithHatchDeficit++;

                    if ((bornCC + buildCC > 0 && hasCcDeficit) || (bornHatch + buildHatch > 0 && hasHatchDeficit)) {
                        StringBuilder line = new StringBuilder();
                        line.append(String.format("  %s p%d [%s]:",
                            replayPath.getFileName(), playerId, race));
                        if (bornCC + buildCC > 0) {
                            line.append(String.format(" CC(born=%d build=%d morph=%d died=%d)", bornCC, buildCC, morphFromCC, diedCC));
                            for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
                                int tickIndex = CHECKPOINTS_MIN[ci] * TICKS_PER_MINUTE - 1;
                                if (tickIndex >= snapshots.size()) continue;
                                var snap = snapshots.get(tickIndex);
                                int gtCC = snap.groundTruthBuildingsByType().getOrDefault(BuildingType.COMMAND_CENTER, 0);
                                int emCC = snap.emulatedBuildingsByType().getOrDefault(BuildingType.COMMAND_CENTER, 0);
                                if (gtCC != emCC) {
                                    line.append(String.format(" @%dmin:GT=%d/EM=%d", CHECKPOINTS_MIN[ci], gtCC, emCC));
                                }
                            }
                        }
                        if (bornHatch + buildHatch > 0) {
                            line.append(String.format(" Hatch(born=%d build=%d morph=%d died=%d)", bornHatch, buildHatch, morphFromHatch, diedHatch));
                            for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
                                int tickIndex = CHECKPOINTS_MIN[ci] * TICKS_PER_MINUTE - 1;
                                if (tickIndex >= snapshots.size()) continue;
                                var snap = snapshots.get(tickIndex);
                                int gtH = snap.groundTruthBuildingsByType().getOrDefault(BuildingType.HATCHERY, 0);
                                int emH = snap.emulatedBuildingsByType().getOrDefault(BuildingType.HATCHERY, 0);
                                if (gtH != emH) {
                                    line.append(String.format(" @%dmin:GT=%d/EM=%d", CHECKPOINTS_MIN[ci], gtH, emH));
                                }
                            }
                        }
                        System.out.println(line);
                    }

                    totalRuns++;
                } catch (Exception e) { /* skip */ }
            }
        }

        System.out.println("\n=== Aggregate Town Hall Counts ===\n");
        System.out.println("| Type | Checkpoint | GT | EM | Delta |");
        System.out.println("|---|---|---|---|---|");
        for (BuildingType bt : TOWN_HALLS) {
            int[] counts = aggregateDelta.get(bt);
            for (int ci = 0; ci < CHECKPOINTS_MIN.length; ci++) {
                int gt = counts[ci * 2];
                int em = counts[ci * 2 + 1];
                System.out.printf("| %s | %d-min | %d | %d | %+d |%n",
                    bt, CHECKPOINTS_MIN[ci], gt, em, em - gt);
            }
        }

        System.out.printf("%n=== Summary ===%n");
        System.out.printf("Total player runs: %d%n", totalRuns);
        System.out.printf("Runs with CC deficit: %d%n", runsWithCcDeficit);
        System.out.printf("Runs with Hatchery deficit: %d%n", runsWithHatchDeficit);
        if (worstCcDeficit > 0) {
            System.out.printf("Worst CC deficit: -%d (%s p%d)%n", worstCcDeficit, worstCcReplay, worstCcPlayer);
        }
        if (worstHatchDeficit > 0) {
            System.out.printf("Worst Hatch deficit: -%d (%s p%d)%n", worstHatchDeficit, worstHatchReplay, worstHatchPlayer);
        }
    }

    @Test
    @EnabledIf("oracleExists")
    void traceWorstCcReplay() throws IOException {
        List<Path> replays;
        try (var stream = Files.list(ORACLE_DIR)) {
            replays = stream.filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted().toList();
        }

        System.out.println("\n=== CC Intent Trace (first replay with CC deficit) ===\n");

        for (Path replayPath : replays) {
            Replay replay;
            try {
                replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.DETAILS));
            } catch (Exception e) { continue; }
            if (replay == null || replay.details == null) continue;
            if (replay.details.getPlayerList().length < 2) continue;

            for (int playerId = 1; playerId <= 2; playerId++) {
                try {
                    Race race = TrackerIntentExtractor.resolvePlayerRace(replay, playerId);
                    if (race != Race.TERRAN) continue;

                    List<TimedIntent> intents = TrackerIntentExtractor.extract(replayPath, playerId);

                    long buildCC = intents.stream().filter(ti -> ti.intent() instanceof BuildIntent b
                        && b.buildingType() == BuildingType.COMMAND_CENTER).count();
                    if (buildCC == 0) continue;

                    ReplaySimulatedGame groundTruth = new ReplaySimulatedGame(replayPath, playerId);
                    List<DivergenceReport.TickSnapshot> snapshots =
                        StandaloneEmulatedGameBaselineTest.runStandalone(
                            groundTruth, intents, TICK_LIMIT, race);

                    int tickAt5 = 5 * TICKS_PER_MINUTE - 1;
                    if (tickAt5 >= snapshots.size()) continue;
                    var snap5 = snapshots.get(tickAt5);
                    int gtCC = snap5.groundTruthBuildingsByType().getOrDefault(BuildingType.COMMAND_CENTER, 0);
                    int emCC = snap5.emulatedBuildingsByType().getOrDefault(BuildingType.COMMAND_CENTER, 0);
                    if (gtCC <= emCC) continue;

                    System.out.printf("Replay: %s, player %d (%s)%n", replayPath.getFileName(), playerId, race);
                    System.out.printf("CC at 5-min: GT=%d, EM=%d (deficit=%d)%n%n", gtCC, emCC, gtCC - emCC);

                    System.out.println("All CC/OC-related intents:");
                    for (TimedIntent ti : intents) {
                        boolean relevant = false;
                        String desc = "";
                        if (ti.intent() instanceof BuildingBornIntent bb && bb.type() == BuildingType.COMMAND_CENTER) {
                            relevant = true;
                            desc = String.format("BuildingBorn CC tag=%s pos=(%.0f,%.0f)", bb.tag(), bb.position().x(), bb.position().y());
                        } else if (ti.intent() instanceof BuildIntent b && b.buildingType() == BuildingType.COMMAND_CENTER) {
                            relevant = true;
                            desc = String.format("Build CC tag=%s replayTag=%s completion=%d pos=(%.0f,%.0f)",
                                b.unitTag(), b.replayTag(), b.completionLoop(), b.location().x(), b.location().y());
                        } else if (ti.intent() instanceof MorphIntent m &&
                            ("COMMAND_CENTER".equals(m.sourceName()) || "OrbitalCommand".equals(m.targetName()) || "PlanetaryFortress".equals(m.targetName()))) {
                            relevant = true;
                            desc = String.format("Morph %s→%s tag=%s completion=%d", m.sourceName(), m.targetName(), m.unitTag(), m.completionLoop());
                        } else if (ti.intent() instanceof BuildingDiedIntent bd && bd.type() == BuildingType.COMMAND_CENTER) {
                            relevant = true;
                            desc = String.format("BuildingDied CC tag=%s", bd.tag());
                        } else if (ti.intent() instanceof BuildingBornIntent bb && bb.type() == BuildingType.ORBITAL_COMMAND) {
                            relevant = true;
                            desc = String.format("BuildingBorn OC tag=%s pos=(%.0f,%.0f)", bb.tag(), bb.position().x(), bb.position().y());
                        }
                        if (relevant) {
                            System.out.printf("  loop=%d (%.1fs) %s%n", ti.loop(), ti.loop() / (double) SC2Data.GAME_LOOPS_PER_SECOND, desc);
                        }
                    }

                    System.out.println("\nEM buildings at each checkpoint:");
                    for (int cp : CHECKPOINTS_MIN) {
                        int tickIndex = cp * TICKS_PER_MINUTE - 1;
                        if (tickIndex >= snapshots.size()) continue;
                        var s = snapshots.get(tickIndex);
                        int gtCCn = s.groundTruthBuildingsByType().getOrDefault(BuildingType.COMMAND_CENTER, 0);
                        int emCCn = s.emulatedBuildingsByType().getOrDefault(BuildingType.COMMAND_CENTER, 0);
                        int gtOC = s.groundTruthBuildingsByType().getOrDefault(BuildingType.ORBITAL_COMMAND, 0);
                        int emOC = s.emulatedBuildingsByType().getOrDefault(BuildingType.ORBITAL_COMMAND, 0);
                        System.out.printf("  %d-min: CC GT=%d EM=%d | OC GT=%d EM=%d%n", cp, gtCCn, emCCn, gtOC, emOC);
                    }
                    System.out.println();
                    return;
                } catch (Exception e) { /* skip */ }
            }
        }
    }
}
