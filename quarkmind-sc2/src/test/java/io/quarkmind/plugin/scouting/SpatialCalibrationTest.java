package io.quarkmind.plugin.scouting;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.SummarisationRunner;
import io.casehub.blocks.summarisation.WindowPolicy;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.GamePhaseSummariser;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.mock.SimulatedGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration harness for spatial features after #298 (real enemy movement).
 *
 * NOT part of the regular test suite. Run explicitly:
 *   mvn test -Pbenchmark -Dtest=SpatialCalibrationTest
 *
 * Measures: posture UNKNOWN rate after first classification, army-near-base
 * event count, and posture transition count across replay datasets.
 */
@Tag("benchmark")
class SpatialCalibrationTest {

    private static final Path AI_ARENA_DIR = Path.of("replays/aiarena_protoss");
    private static final Path IEM10_ZIP    = Path.of("replays/2016_IEM_10_Taipei.zip");
    private static final int TICKS_FULL_GAME = 900;

    record SpatialMetrics(
        String replayName,
        String matchup,
        int totalFramesAfterFirstClassification,
        int unknownFramesAfterFirstClassification,
        int armyNearBaseEventCount,
        int postureTransitions,
        int tacticalTransitions,
        String tacticalSequence
    ) {
        double unknownRate() {
            return totalFramesAfterFirstClassification == 0 ? 0.0
                : (double) unknownFramesAfterFirstClassification / totalFramesAfterFirstClassification;
        }
    }

    @Test
    void spatialMetrics() throws IOException {
        Map<String, List<SpatialMetrics>> metricsByMatchup = new LinkedHashMap<>();
        metricsByMatchup.put("PvT", new ArrayList<>());
        metricsByMatchup.put("PvZ", new ArrayList<>());
        metricsByMatchup.put("PvP", new ArrayList<>());

        int aiArenaLoaded = 0, aiArenaSkipped = 0;

        List<Path> replayFiles = Files.list(AI_ARENA_DIR)
            .filter(p -> p.toString().endsWith(".SC2Replay"))
            .sorted()
            .collect(Collectors.toList());

        for (Path replay : replayFiles) {
            try {
                ReplaySimulatedGame game = new ReplaySimulatedGame(replay, 1);
                SpatialMetrics m = measureReplay(game, replay.getFileName().toString(), "PvP");
                metricsByMatchup.get("PvP").add(m);
                aiArenaLoaded++;
            } catch (IllegalArgumentException e) {
                aiArenaSkipped++;
            }
        }

        List<IEM10JsonSimulatedGame> iem10Games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        for (IEM10JsonSimulatedGame game : iem10Games) {
            SpatialMetrics m = measureReplay(game, game.replayName(), game.matchup());
            metricsByMatchup.get(game.matchup()).add(m);
        }

        String report = buildReport(metricsByMatchup, aiArenaLoaded, aiArenaSkipped, iem10Games.size());
        System.out.println(report);

        int total = metricsByMatchup.values().stream().mapToInt(List::size).sum();
        assertThat(total).as("Total replays loaded").isGreaterThan(0);
    }

    private SpatialMetrics measureReplay(SimulatedGame game, String name, String matchup) {
        ScoutingSessionManager mgr = new ScoutingSessionManager();
        boolean firstClassified = false;
        int totalAfter = 0;
        int unknownAfter = 0;
        int armyNearBase = 0;
        int postureTransitions = 0;
        String lastPosture = "UNKNOWN";

        EventLevel level2 = new EventLevel("moment", 2);
        EventLevel level3 = new EventLevel("phase", 3);
        EventStreamBus<GameMoment> momentBus = new EventStreamBus<>();
        EventStreamBus<TacticalPosture> phaseBus = new EventStreamBus<>();
        SummarisationRunner<GameMoment, TacticalPosture> phaseRunner = new SummarisationRunner<>(
            new WindowPolicy(672, 5), new GamePhaseSummariser(), phaseBus, level3);
        momentBus.subscribe(m -> true, phaseRunner::collect);
        List<TacticalPosture> phases = new ArrayList<>();
        phaseBus.subscribe(p -> true, e -> phases.add(e.payload()));

        boolean prevTimingState = false;
        String prevEconPosture = null;

        Point2d ourNexus = new Point2d(8, 8);
        Point2d estimatedEnemyBase = new Point2d(224, 224);

        for (int tick = 0; tick < TICKS_FULL_GAME; tick++) {
            game.tick();
            GameState state = game.snapshot();
            long gameFrame = state.gameFrame();

            List<Unit> enemies = state.enemyUnits();
            long gameTimeMs = (long)(gameFrame * (1000.0 / 22.4));

            int prevArmyBuffer = mgr.armyBufferSize();
            mgr.processFrame(enemies, gameTimeMs, ourNexus, estimatedEnemyBase);
            mgr.evict(gameTimeMs);

            if (mgr.armyBufferSize() > prevArmyBuffer) {
                armyNearBase++;
            }

            boolean hasUnits = mgr.unitBufferSize() > 0;
            boolean hasExpansions = mgr.expansionBufferSize() > 0;
            String posture;
            if (hasUnits && !hasExpansions) {
                posture = "ALL_IN";
            } else if (hasExpansions) {
                posture = "MACRO";
            } else {
                posture = "UNKNOWN";
            }

            if (!posture.equals("UNKNOWN") && !firstClassified) {
                firstClassified = true;
            }

            if (firstClassified) {
                totalAfter++;
                if (posture.equals("UNKNOWN")) {
                    unknownAfter++;
                }
            }

            if (!posture.equals(lastPosture)) {
                postureTransitions++;
                lastPosture = posture;
            }

            boolean currentTimingState = mgr.armyBufferSize() > 0;
            if (currentTimingState && !prevTimingState) {
                momentBus.publish(new LevelEvent<>(
                    new GameMoment(GameMomentType.BATTLE_STARTED, gameFrame, Map.of()),
                    gameFrame, level2, "default"));
                momentBus.publish(new LevelEvent<>(
                    new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, gameFrame, Map.of()),
                    gameFrame, level2, "default"));
            }
            prevTimingState = currentTimingState;

            if (posture.equals("ALL_IN") && !"ALL_IN".equals(prevEconPosture)) {
                momentBus.publish(new LevelEvent<>(
                    new GameMoment(GameMomentType.ECONOMIC_CRISIS, gameFrame, Map.of()),
                    gameFrame, level2, "default"));
            }
            prevEconPosture = posture;

            phaseRunner.tick(gameFrame);
        }

        int tacticalTransitions = 0;
        StringBuilder tacticalSeq = new StringBuilder();
        String lastTactical = null;
        for (TacticalPosture tp : phases) {
            if (!tp.posture().equals(lastTactical)) {
                tacticalTransitions++;
                if (tacticalSeq.length() > 0) tacticalSeq.append("→");
                tacticalSeq.append(tp.posture());
                lastTactical = tp.posture();
            }
        }

        return new SpatialMetrics(name, matchup, totalAfter, unknownAfter,
            armyNearBase, postureTransitions, tacticalTransitions,
            tacticalSeq.length() > 0 ? tacticalSeq.toString() : "—");
    }

    private String buildReport(Map<String, List<SpatialMetrics>> metricsByMatchup,
                               int aiArenaLoaded, int aiArenaSkipped, int iem10Loaded) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== SPATIAL CALIBRATION REPORT ===\n");
        sb.append(String.format("Replays loaded: AI Arena=%d (skipped %d), IEM10=%d\n\n",
            aiArenaLoaded, aiArenaSkipped, iem10Loaded));

        for (var entry : metricsByMatchup.entrySet()) {
            String matchup = entry.getKey();
            List<SpatialMetrics> metrics = entry.getValue();
            if (metrics.isEmpty()) continue;

            sb.append(String.format("--- %s (%d replays) ---\n", matchup, metrics.size()));
            sb.append(String.format("%-40s %8s %8s %8s %8s %8s  %s\n",
                "Replay", "Unk%", "ArmyEvt", "PostTrn", "Frames", "TacTrn", "TacticalPosture sequence"));

            for (SpatialMetrics m : metrics) {
                sb.append(String.format("%-40s %7.1f%% %8d %8d %8d %8d  %s\n",
                    truncate(m.replayName(), 40),
                    m.unknownRate() * 100,
                    m.armyNearBaseEventCount(),
                    m.postureTransitions(),
                    m.totalFramesAfterFirstClassification(),
                    m.tacticalTransitions(),
                    m.tacticalSequence()));
            }

            double avgUnknown = metrics.stream().mapToDouble(SpatialMetrics::unknownRate).average().orElse(0);
            double avgArmy = metrics.stream().mapToDouble(SpatialMetrics::armyNearBaseEventCount).average().orElse(0);
            double avgTransitions = metrics.stream().mapToDouble(SpatialMetrics::postureTransitions).average().orElse(0);
            double avgTactical = metrics.stream().mapToDouble(SpatialMetrics::tacticalTransitions).average().orElse(0);

            sb.append(String.format("\n  Avg UNKNOWN rate: %.1f%%\n", avgUnknown * 100));
            sb.append(String.format("  Avg army-near-base events: %.1f\n", avgArmy));
            sb.append(String.format("  Avg posture transitions: %.1f\n", avgTransitions));
            sb.append(String.format("  Avg tactical posture transitions: %.1f\n\n", avgTactical));
        }

        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
