package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.EnemyPatternAssessment;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.mock.SimulatedGame;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("benchmark")
class PatternClassificationCalibrationTest {

    @Inject
    RuleUnit<PatternClassificationRuleUnit> ruleUnit;

    private static final Path   AI_ARENA_DIR      = Path.of("replays/aiarena_protoss");
    private static final Path   IEM10_ZIP         = Path.of("replays/2016_IEM_10_Taipei.zip");
    private static final int    TICKS_3MIN        = 183;
    private static final double GAME_TIME_3MIN    = 3.0;
    private static final double FRAMES_PER_SECOND = 22.4;

    // --- Ground truth unit tests (no CDI needed, but run inside @QuarkusTest) ---

    @Test
    void groundTruth_roachRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.ROACH, 5L);
        assertThat(deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.ZERG_ROACH_RUSH);
    }

    @Test
    void groundTruth_marineRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MARINE, 6L);
        assertThat(deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.TERRAN_MARINE_RUSH);
    }

    @Test
    void groundTruth_noPattern() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.ZEALOT, 1L);
        assertThat(deriveGroundTruth(counts, 3.0)).isNull();
    }

    @Test
    void groundTruth_zerglingRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.ZERGLING, 7L);
        assertThat(deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.ZERG_ZERGLING_RUSH);
    }

    @Test
    void groundTruth_gatewayRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.STALKER, 2L);
        counts.put(UnitType.ZEALOT, 3L);
        assertThat(deriveGroundTruth(counts, 4.0)).isEqualTo(StrategyArchetype.PROTOSS_GATEWAY_RUSH);
    }

    @Test
    void groundTruth_bansheeHarass() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.BANSHEE, 1L);
        assertThat(deriveGroundTruth(counts, 6.0)).isEqualTo(StrategyArchetype.TERRAN_BANSHEE_HARASS);
    }

    @Test
    void groundTruth_bioTiming_marinesLate() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MARINE, 7L);
        assertThat(deriveGroundTruth(counts, 5.0)).isEqualTo(StrategyArchetype.TERRAN_BIO_TIMING);
    }

    @Test
    void groundTruth_mechPush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.SIEGE_TANK, 3L);
        assertThat(deriveGroundTruth(counts, 6.0)).isEqualTo(StrategyArchetype.TERRAN_MECH_PUSH);
    }

    @Test
    void groundTruth_precedence_marineRushBeforeBioTiming() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MARINE, 6L);
        assertThat(deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.TERRAN_MARINE_RUSH);
    }

    // --- Classification accuracy calibration ---

    @Test
    void calibrateClassificationAccuracy() throws IOException {
        int           rushCorrect = 0, rushTotal = 0;
        int           airCorrect  = 0, airTotal = 0;
        int           totalGames  = 0, labelledGames = 0;
        StringBuilder report      = new StringBuilder();
        report.append("=== Pattern Classification Accuracy — 3-min mark ===\n\n");

        List<Path> replayFiles = Files.list(AI_ARENA_DIR)
                                      .filter(p -> p.toString().endsWith(".SC2Replay"))
                                      .sorted().toList();

        for (Path replay : replayFiles) {
            try {
                ReplaySimulatedGame game   = new ReplaySimulatedGame(replay, 1);
                var                 result = classifyGame(game, "PvP", replay.getFileName().toString());
                totalGames++;
                if (result != null) {
                    labelledGames++;
                    report.append(result.reportLine()).append("\n");
                    if (result.isRush()) {
                        rushTotal++;
                        if (result.correct()) {rushCorrect++;}
                    }
                    if (result.isAirThreat()) {
                        airTotal++;
                        if (result.correct()) {airCorrect++;}
                    }
                }
            } catch (IllegalArgumentException e) { /* skip unparseable */ }
        }

        List<IEM10JsonSimulatedGame> iem10Games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        for (IEM10JsonSimulatedGame game : iem10Games) {
            var result = classifyGame(game, game.matchup(), game.replayName());
            totalGames++;
            if (result != null) {
                labelledGames++;
                report.append(result.reportLine()).append("\n");
                if (result.isRush()) {
                    rushTotal++;
                    if (result.correct()) {rushCorrect++;}
                }
                if (result.isAirThreat()) {
                    airTotal++;
                    if (result.correct()) {airCorrect++;}
                }
            }
        }

        double rushAccuracy = rushTotal > 0 ? (double) rushCorrect / rushTotal : 1.0;
        double airAccuracy  = airTotal > 0 ? (double) airCorrect / airTotal : 1.0;

        report.append("\n=== Summary ===\n");
        report.append(String.format("Total games: %d, labelled: %d%n", totalGames, labelledGames));
        report.append(String.format("Rush accuracy: %d/%d = %.1f%%%n", rushCorrect, rushTotal, rushAccuracy * 100));
        report.append(String.format("Air threat accuracy: %d/%d = %.1f%%%n", airCorrect, airTotal, airAccuracy * 100));
        System.out.println(report);

        assertThat(rushTotal).as("Rush sample size").isGreaterThan(0);
        assertThat(rushAccuracy).as("Rush archetype accuracy").isGreaterThanOrEqualTo(0.7);
        if (airTotal > 0) {
            assertThat(airAccuracy).as("Air threat accuracy").isGreaterThanOrEqualTo(0.7);
        }
    }

    private ClassificationResult classifyGame(SimulatedGame game, String matchup, String gameName) {
        ScoutingSessionManager          sessionManager = new ScoutingSessionManager();
        EnumMap<StrategyArchetype, Double> cumulative     = new EnumMap<>(StrategyArchetype.class);
        Point2d                         ourNexus       = new Point2d(30, 30);
        Point2d                         enemyBase      = new Point2d(120, 120);

        for (int tick = 0; tick < TICKS_3MIN; tick++) {
            game.tick();
            GameState state       = game.snapshot();
            long      gameTimeMs  = (long) (state.gameFrame() * (1000.0 / FRAMES_PER_SECOND));
            double    gameTimeMin = gameTimeMs / 60000.0;

            sessionManager.processFrame(state.enemyUnits(), gameTimeMs, ourNexus, enemyBase);
            sessionManager.evict(gameTimeMs);

            PatternClassificationRuleUnit data = sessionManager.buildPatternRuleUnit(gameTimeMin);
            try (RuleUnitInstance<PatternClassificationRuleUnit> instance = ruleUnit.createInstance(data)) {
                instance.fire();
            }
            var tickConf = PatternClassifier.computeAllConfidences(data.getEvidence());
            long prevFrame = tick == 0 ? -1 : state.gameFrame() - 1;
            PatternClassifier.mergeCumulative(cumulative, tickConf, state.gameFrame(), prevFrame);
            long framesElapsed = prevFrame >= 0 ? state.gameFrame() - prevFrame : 0;
            PatternClassifier.applyRevisions(cumulative, data.getRevisions(), framesElapsed);
        }

        GameState           finalState = game.snapshot();
        Map<UnitType, Long> counts     = new EnumMap<>(UnitType.class);
        for (var unit : finalState.enemyUnits()) {
            counts.merge(unit.type(), 1L, Long::sum);
        }
        StrategyArchetype groundTruth = deriveGroundTruth(counts, GAME_TIME_3MIN);
        if (groundTruth == null) {return null;}

        var            assessments = PatternClassifier.allAssessments(cumulative, TICKS_3MIN);
        StrategyArchetype predicted   = assessments.isEmpty() ? null : assessments.get(0).archetype();
        boolean        correct     = groundTruth == predicted;

        return new ClassificationResult(matchup, gameName, groundTruth, predicted, correct,
                                        assessments.isEmpty() ? 0.0 : assessments.get(0).confidence());
    }

    private record ClassificationResult(String matchup, String gameName,
                                        StrategyArchetype groundTruth, StrategyArchetype predicted,
                                        boolean correct, double confidence) {
        boolean isRush() {
            return groundTruth == StrategyArchetype.TERRAN_MARINE_RUSH
                   || groundTruth == StrategyArchetype.ZERG_ZERGLING_RUSH
                   || groundTruth == StrategyArchetype.ZERG_ROACH_RUSH
                   || groundTruth == StrategyArchetype.PROTOSS_GATEWAY_RUSH;
        }

        boolean isAirThreat() {
            return groundTruth == StrategyArchetype.TERRAN_BANSHEE_HARASS;
        }

        String reportLine() {
            return String.format("  %-4s %-40s truth=%-24s pred=%-24s conf=%.2f %s",
                                 matchup, gameName, groundTruth, predicted, confidence, correct ? "✓" : "✗");
        }
    }

    static StrategyArchetype deriveGroundTruth(Map<UnitType, Long> counts, double gameTimeMin) {
        long marines    = counts.getOrDefault(UnitType.MARINE, 0L);
        long roaches    = counts.getOrDefault(UnitType.ROACH, 0L);
        long zerglings  = counts.getOrDefault(UnitType.ZERGLING, 0L);
        long stalkers   = counts.getOrDefault(UnitType.STALKER, 0L);
        long zealots    = counts.getOrDefault(UnitType.ZEALOT, 0L);
        long siegeTanks = counts.getOrDefault(UnitType.SIEGE_TANK, 0L);
        long banshees   = counts.getOrDefault(UnitType.BANSHEE, 0L);

        if (marines >= 5 && gameTimeMin < 4.0) {return StrategyArchetype.TERRAN_MARINE_RUSH;}
        if (banshees >= 1 && gameTimeMin < 8.0) {return StrategyArchetype.TERRAN_BANSHEE_HARASS;}
        if (zerglings >= 6 && gameTimeMin < 4.0) {return StrategyArchetype.ZERG_ZERGLING_RUSH;}
        if (roaches >= 4 && gameTimeMin < 5.0) {return StrategyArchetype.ZERG_ROACH_RUSH;}
        if (stalkers + zealots >= 4 && gameTimeMin < 5.0) {return StrategyArchetype.PROTOSS_GATEWAY_RUSH;}
        if (marines >= 6 && gameTimeMin >= 4.0) {return StrategyArchetype.TERRAN_BIO_TIMING;}
        if (siegeTanks >= 2 && gameTimeMin >= 5.0) {return StrategyArchetype.TERRAN_MECH_PUSH;}
        return null;
    }
}
