package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.StrategyArchetype;
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
    @Inject
    io.quarkmind.agent.StrategyTaxonomy taxonomy;


    private static final Path AI_ARENA_DIR = ReplayClassificationTestSupport.AI_ARENA_DIR;
    private static final Path IEM10_ZIP = ReplayClassificationTestSupport.IEM10_ZIP;
    private static final int TICKS_3MIN = 3 * ReplayClassificationTestSupport.TICKS_PER_MINUTE;
    private static final int    TICKS_8MIN        = 488;
    private static final int    TICKS_15MIN       = 916;
    private static final double GAME_TIME_3MIN    = 3.0;
    private static final double GAME_TIME_8MIN    = 8.0;
    private static final double GAME_TIME_15MIN   = 15.0;
    private static final double FRAMES_PER_SECOND = ReplayClassificationTestSupport.FRAMES_PER_SECOND;

    // --- Ground truth unit tests (no CDI needed, but run inside @QuarkusTest) ---

    @Test
    void groundTruth_roachRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.ROACH, 5L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.ZERG_ROACH_RUSH);
    }

    @Test
    void groundTruth_marineRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MARINE, 6L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.TERRAN_MARINE_RUSH);
    }

    @Test
    void groundTruth_noPattern() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.ZEALOT, 1L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 3.0)).isNull();
    }

    @Test
    void groundTruth_zerglingRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.ZERGLING, 7L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.ZERG_ZERGLING_RUSH);
    }

    @Test
    void groundTruth_gatewayRush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.STALKER, 2L);
        counts.put(UnitType.ZEALOT, 3L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 4.0)).isEqualTo(StrategyArchetype.PROTOSS_GATEWAY_RUSH);
    }

    @Test
    void groundTruth_bansheeHarass() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.BANSHEE, 1L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 6.0)).isEqualTo(StrategyArchetype.TERRAN_BANSHEE_HARASS);
    }

    @Test
    void groundTruth_bioTiming_marinesLate() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MARINE, 7L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 5.0)).isEqualTo(StrategyArchetype.TERRAN_BIO_TIMING);
    }

    @Test
    void groundTruth_mechPush() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.SIEGE_TANK, 3L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 6.0)).isEqualTo(StrategyArchetype.TERRAN_MECH_PUSH);
    }

    @Test
    void groundTruth_precedence_marineRushBeforeBioTiming() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MARINE, 6L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 3.0)).isEqualTo(StrategyArchetype.TERRAN_MARINE_RUSH);
    }

    @Test
    void groundTruth_marineTank() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MARINE, 8L);
        counts.put(UnitType.SIEGE_TANK, 3L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 7.0)).isEqualTo(StrategyArchetype.TERRAN_MARINE_TANK);
    }

    @Test
    void groundTruth_roachHydra() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.ROACH, 4L);
        counts.put(UnitType.HYDRALISK, 3L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 7.0)).isEqualTo(StrategyArchetype.ZERG_ROACH_HYDRA);
    }

    @Test
    void groundTruth_mutaliskHarass() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.MUTALISK, 4L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 6.0)).isEqualTo(StrategyArchetype.ZERG_MUTALISK_HARASS);
    }

    @Test
    void groundTruth_bcTransition() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.BATTLECRUISER, 1L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 12.0)).isEqualTo(StrategyArchetype.TERRAN_BC_TRANSITION);
    }

    @Test
    void groundTruth_broodLord() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.BROOD_LORD, 3L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 15.0)).isEqualTo(StrategyArchetype.ZERG_BROOD_LORD);
    }

    @Test
    void groundTruth_dtHarass() {
        var counts = new EnumMap<UnitType, Long>(UnitType.class);
        counts.put(UnitType.DARK_TEMPLAR, 1L);
        assertThat(ReplayClassificationTestSupport.deriveGroundTruth(counts, 5.0)).isEqualTo(StrategyArchetype.PROTOSS_DT_HARASS);
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
                var                 result = classifyGame(game, "PvP", replay.getFileName().toString(), TICKS_3MIN, GAME_TIME_3MIN);
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
            var result = classifyGame(game, game.matchup(), game.replayName(), TICKS_3MIN, GAME_TIME_3MIN);
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

    @Test
    void droolsBaselinePerMinuteAccuracy() throws IOException {
        int[]         checkpointMinutes = {1, 2, 3, 4, 5};
        StringBuilder report            = new StringBuilder();
        report.append("=== Drools-Only Baseline — Per-Minute Classification Accuracy ===\n");
        report.append("Establishes reference numbers for #213 (IEM10 replay validation & accuracy benchmarking)\n\n");

        Map<String, Map<Integer, int[]>> matchupAccuracy = new java.util.LinkedHashMap<>();
        for (String m : List.of("PvT", "PvZ", "PvP")) {
            matchupAccuracy.put(m, new java.util.LinkedHashMap<>());
            for (int min : checkpointMinutes) {
                matchupAccuracy.get(m).put(min, new int[]{0, 0});
            }
        }

        List<IEM10JsonSimulatedGame> iem10Games = IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
        for (IEM10JsonSimulatedGame game : iem10Games) {
            game.reset();
            for (int min : checkpointMinutes) {
                int targetTicks = min * (TICKS_3MIN / 3);
                var result      = classifyGame(game, game.matchup(), game.replayName(), targetTicks, (double) min);
                if (result != null) {
                    int[] counts = matchupAccuracy.getOrDefault(game.matchup(), new java.util.LinkedHashMap<>())
                                                  .get(min);
                    if (counts != null) {
                        counts[1]++;
                        if (result.correct()) {counts[0]++;}
                    }
                }
                game.reset();
            }
        }

        report.append(String.format("%-6s", "Min"));
        for (String m : matchupAccuracy.keySet()) {
            report.append(String.format("  %-12s", m));
        }
        report.append("  All\n");

        for (int min : checkpointMinutes) {
            report.append(String.format("%-6d", min));
            int allCorrect = 0, allTotal = 0;
            for (String m : matchupAccuracy.keySet()) {
                int[] counts = matchupAccuracy.get(m).get(min);
                allCorrect += counts[0];
                allTotal += counts[1];
                if (counts[1] > 0) {
                    report.append(String.format("  %d/%d=%.0f%%    ", counts[0], counts[1], (double) counts[0] / counts[1] * 100));
                } else {
                    report.append(String.format("  %-12s", "—"));
                }
            }
            if (allTotal > 0) {
                report.append(String.format("  %d/%d=%.0f%%", allCorrect, allTotal, (double) allCorrect / allTotal * 100));
            }
            report.append("\n");
        }

        System.out.println(report);
    }


    private ReplayClassificationTestSupport.ClassificationResult classifyGame(
            SimulatedGame game, String matchup, String gameName,
            int targetTicks, double targetGameTimeMin) {
        ScoutingSessionManager             sessionManager = new ScoutingSessionManager();
        EnumMap<StrategyArchetype, Double> cumulative     = new EnumMap<>(StrategyArchetype.class);
        Point2d                            ourNexus       = new Point2d(30, 30);
        Point2d                            enemyBase      = new Point2d(120, 120);

        for (int tick = 0; tick < targetTicks; tick++) {
            game.tick();
            GameState state       = game.snapshot();
            long      gameTimeMs  = (long) (state.gameFrame() * (1000.0 / FRAMES_PER_SECOND));
            double    gameTimeMin = gameTimeMs / 60000.0;

            sessionManager.processFrame(state.enemyUnits(), gameTimeMs, ourNexus, enemyBase);
            sessionManager.evict(gameTimeMs);

            PatternClassificationRuleUnit data = sessionManager.buildPatternRuleUnit(gameTimeMin);
            taxonomy.activeSignatures(gameTimeMin).forEach(data.getSignatureStore()::add);
            try (RuleUnitInstance<PatternClassificationRuleUnit> instance = ruleUnit.createInstance(data)) {
                instance.fire();
            }
            var  tickConf  = CascadingPatternClassifier.computeAllConfidences(data.getEvidence());
            long prevFrame = tick == 0 ? -1 : state.gameFrame() - 1;
            CascadingPatternClassifier.mergeCumulative(cumulative, tickConf, state.gameFrame(), prevFrame);
            long framesElapsed = prevFrame >= 0 ? state.gameFrame() - prevFrame : 0;
            CascadingPatternClassifier.applyRevisions(cumulative, data.getRevisions(), framesElapsed);
        }

        GameState           finalState = game.snapshot();
        Map<UnitType, Long> counts     = new EnumMap<>(UnitType.class);
        for (var unit : finalState.enemyUnits()) {
            counts.merge(unit.type(), 1L, Long::sum);
        }
        StrategyArchetype groundTruth = ReplayClassificationTestSupport.deriveGroundTruth(counts, targetGameTimeMin);
        if (groundTruth == null) {return null;}

        var               assessments = CascadingPatternClassifier.allAssessments(cumulative, targetTicks, AssessmentSource.DROOLS);
        StrategyArchetype predicted   = assessments.isEmpty() ? null : assessments.get(0).archetype();
        boolean           correct     = groundTruth == predicted;

        return new ReplayClassificationTestSupport.ClassificationResult(matchup, gameName, groundTruth, predicted, correct,
                                                                        assessments.isEmpty() ? 0.0 : assessments.get(0).confidence());
    }

}
