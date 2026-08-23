package io.quarkmind.plugin.scouting;

import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.TensorClassifier;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkmind.sc2.mock.SimulatedGame;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.quarkmind.plugin.scouting.ReplayClassificationTestSupport.ClassificationResult;
import static io.quarkmind.plugin.scouting.ReplayClassificationTestSupport.FRAMES_PER_SECOND;
import static io.quarkmind.plugin.scouting.ReplayClassificationTestSupport.TICKS_PER_MINUTE;
import static io.quarkmind.plugin.scouting.ReplayClassificationTestSupport.deriveGroundTruth;
import static io.quarkmind.plugin.scouting.ReplayClassificationTestSupport.enemyRaceFromMatchup;
import static io.quarkmind.plugin.scouting.ReplayClassificationTestSupport.loadAIArenaGames;
import static io.quarkmind.plugin.scouting.ReplayClassificationTestSupport.loadIEM10Games;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("benchmark")
class CascadeValidationCalibrationTest {

    @Inject
    RuleUnit<PatternClassificationRuleUnit> ruleUnit;
    @Inject
    io.quarkmind.agent.StrategyTaxonomy taxonomy;

    private static final int[] CHECKPOINT_MINUTES = {1, 2, 3, 4, 5};
    private static final int TICKS_5MIN = 5 * TICKS_PER_MINUTE;
    private static final Path MODELS_DIR = Path.of("src/test/resources/models/strategy");

    private static EnumMap<Race, TensorClassifier> onnxClassifiers;
    private static OnnxInferenceModel terranModel;
    private static OnnxInferenceModel zergModel;
    private static OnnxInferenceModel protossModel;

    @BeforeAll
    static void loadModels() {
        terranModel = new OnnxInferenceModel(new ModelConfig(MODELS_DIR.resolve("strategy_vs_terran.onnx")));
        zergModel = new OnnxInferenceModel(new ModelConfig(MODELS_DIR.resolve("strategy_vs_zerg.onnx")));
        protossModel = new OnnxInferenceModel(new ModelConfig(MODELS_DIR.resolve("strategy_vs_protoss.onnx")));
        onnxClassifiers = new EnumMap<>(Race.class);
        onnxClassifiers.put(Race.TERRAN, new TensorClassifier(terranModel, OnnxLabelMapping.VS_TERRAN_LABELS));
        onnxClassifiers.put(Race.ZERG, new TensorClassifier(zergModel, OnnxLabelMapping.VS_ZERG_LABELS));
        onnxClassifiers.put(Race.PROTOSS, new TensorClassifier(protossModel, OnnxLabelMapping.VS_PROTOSS_LABELS));
    }

    @AfterAll
    static void closeModels() {
        if (terranModel != null) terranModel.close();
        if (zergModel != null) zergModel.close();
        if (protossModel != null) protossModel.close();
    }

    @Test
    void validateCascadeAccuracy() throws IOException {
        List<IEM10JsonSimulatedGame> iem10Games   = loadIEM10Games();
        List<ReplaySimulatedGame>    aiArenaGames = loadAIArenaGames();

        var droolsOnly = new CascadingPatternClassifier(0.0, 1.0);
        var onnxOnly   = new CascadingPatternClassifier(1.0, 0.0, onnxClassifiers);
        var cascade    = new CascadingPatternClassifier(0.7, 0.5, onnxClassifiers);

        StringBuilder report = new StringBuilder();
        report.append("=== Cascade Validation — IEM10 + AI Arena (59 games) ===\n\n");

        var iem10DroolsResults  = runMode("DROOLS_ONLY", droolsOnly, iem10Games, aiArenaGames, report);
        var iem10OnnxResults    = runMode("ONNX_ONLY", onnxOnly, iem10Games, aiArenaGames, report);
        var iem10CascadeResults = runMode("CASCADE", cascade, iem10Games, aiArenaGames, report);

        appendComparisonBaselines(report, iem10DroolsResults, iem10OnnxResults, iem10CascadeResults);

        System.out.println(report);

        double droolsRushMin3 = rushAccuracyAtMinute(iem10DroolsResults.iem10(), 3);
        assertThat(droolsRushMin3).as("Drools-only rush ≥ 70% at min 3").isGreaterThanOrEqualTo(0.70);

        // AI Arena cascade accuracy — informational only (R1-06: ONNX models trained on
        // modern replays; cross-era accuracy assertion is premature until baseline analyzed)
        double aiArenaDroolsMin4  = overallAccuracyAtMinute(iem10DroolsResults.aiArena(), 4);
        double aiArenaCascadeMin4 = overallAccuracyAtMinute(iem10CascadeResults.aiArena(), 4);
        System.out.printf("AI Arena Drools-only min 4: %.0f%%%n", aiArenaDroolsMin4 * 100);
        System.out.printf("AI Arena Cascade min 4: %.0f%% (informational — not asserted)%n", aiArenaCascadeMin4 * 100);
    }

    private record ModeResults(
            Map<Integer, List<ClassificationResult>> iem10,
            Map<Integer, List<ClassificationResult>> aiArena,
            Map<Integer, TierCounts> tierHits
    ) {}

    private record TierCounts(int drools, int onnx, int llm, int fallback, int total) {}

    private ModeResults runMode(String modeName,
                                CascadingPatternClassifier classifier,
                                List<IEM10JsonSimulatedGame> iem10Games,
                                List<ReplaySimulatedGame> aiArenaGames,
                                StringBuilder report) {
        Map<Integer, List<ClassificationResult>> iem10Results = new LinkedHashMap<>();
        Map<Integer, List<ClassificationResult>> aiArenaResults = new LinkedHashMap<>();
        Map<Integer, TierCounts> tierHits = new LinkedHashMap<>();
        for (int min : CHECKPOINT_MINUTES) {
            iem10Results.put(min, new java.util.ArrayList<>());
            aiArenaResults.put(min, new java.util.ArrayList<>());
        }

        for (IEM10JsonSimulatedGame game : iem10Games) {
            Race enemyRace = enemyRaceFromMatchup(game.matchup());
            runGameAcrossMinutes(classifier, game, game.matchup(), game.replayName(), enemyRace, iem10Results);
        }

        for (ReplaySimulatedGame game : aiArenaGames) {
            runGameAcrossMinutes(classifier, game, "PvP", "AI-Arena", Race.PROTOSS, aiArenaResults);
        }

        if (modeName.equals("CASCADE")) {
            for (int min : CHECKPOINT_MINUTES) {
                tierHits.put(min, computeTierHits(iem10Games, aiArenaGames, min));
            }
        }

        report.append(String.format("Mode: %s%n", modeName));
        appendAccuracyTable(report, iem10Results, "IEM10");
        appendAccuracyTable(report, aiArenaResults, "AI Arena (PvP)");
        report.append("\n");

        return new ModeResults(iem10Results, aiArenaResults, tierHits);
    }

    private void runGameAcrossMinutes(CascadingPatternClassifier classifier,
                                      SimulatedGame game, String matchup, String gameName,
                                      Race enemyRace,
                                      Map<Integer, List<ClassificationResult>> resultsByMinute) {
        var sessionManager = new ScoutingSessionManager();
        var accumulator = new TemporalWindowAccumulator();
        var featureExtractor = new StrategyFeatureExtractor();
        Point2d ourNexus = new Point2d(30, 30);
        Point2d enemyBase = new Point2d(120, 120);

        classifier.reset();
        game.reset();

        int nextCheckpointIdx = 0;
        long prevFrame = -1;

        for (int tick = 0; tick < TICKS_5MIN && nextCheckpointIdx < CHECKPOINT_MINUTES.length; tick++) {
            game.tick();
            GameState state = game.snapshot();
            long gameTimeMs = (long) (state.gameFrame() * (1000.0 / FRAMES_PER_SECOND));
            double gameTimeMin = gameTimeMs / 60000.0;

            sessionManager.processFrame(state.enemyUnits(), gameTimeMs, ourNexus, enemyBase);
            sessionManager.evict(gameTimeMs);

            PatternClassificationRuleUnit patternData = sessionManager.buildPatternRuleUnit(gameTimeMin);
            taxonomy.activeSignatures(gameTimeMin).forEach(patternData.getSignatureStore()::add);
            try (RuleUnitInstance<PatternClassificationRuleUnit> instance = ruleUnit.createInstance(patternData)) {
                instance.fire();
            }

            accumulator.addSnapshot(DroolsScoutingTask.buildSnapshot(state));
            var features = featureExtractor.extract(accumulator.getWindowedFeatures(), MapCharacteristics.DEFAULT);
            CascadeResult cascadeResult = classifier.classify(
                    patternData.getEvidence(), patternData.getRevisions(),
                    features, enemyRace, state.gameFrame(), prevFrame, null);
            prevFrame = state.gameFrame();

            int targetTick = CHECKPOINT_MINUTES[nextCheckpointIdx] * TICKS_PER_MINUTE;
            if (tick + 1 >= targetTick) {
                int min = CHECKPOINT_MINUTES[nextCheckpointIdx];
                Map<UnitType, Long> counts = new EnumMap<>(UnitType.class);
                for (var unit : state.enemyUnits()) {
                    counts.merge(unit.type(), 1L, Long::sum);
                }
                StrategyArchetype groundTruth = deriveGroundTruth(counts, (double) min);
                if (groundTruth != null) {
                    List<PatternAssessment> assessments = cascadeResult.assessments();
                    StrategyArchetype predicted = assessments.isEmpty() ? null : assessments.get(0).archetype();
                    boolean correct = groundTruth == predicted;
                    double confidence = assessments.isEmpty() ? 0.0 : assessments.get(0).confidence();
                    AssessmentSource source = assessments.isEmpty() ? AssessmentSource.DROOLS : assessments.get(0).source();
                    resultsByMinute.get(min).add(new ClassificationResult(
                            matchup, gameName, groundTruth, predicted, correct, confidence));
                }
                nextCheckpointIdx++;
            }
        }
    }

    private TierCounts computeTierHits(List<IEM10JsonSimulatedGame> iem10Games,
                                       List<ReplaySimulatedGame> aiArenaGames,
                                       int targetMinute) {
        int drools      = 0, onnx = 0, llm = 0, fallback = 0, total = 0;
        int targetTicks = targetMinute * TICKS_PER_MINUTE;

        var classifier = new CascadingPatternClassifier(0.7, 0.5, onnxClassifiers);
        for (IEM10JsonSimulatedGame game : iem10Games) {
            AssessmentSource source = classifyToSource(classifier, game, game.matchup(), targetTicks);
            total++;
            switch (source) {
                case DROOLS -> drools++;
                case ONNX -> onnx++;
                case LLM -> llm++;
            }
            if (source == AssessmentSource.DROOLS) {
                var    snap = classifier.snapshotConfidences();
                double max  = snap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                if (max < 0.7) {fallback++;}
            }
        }

        for (ReplaySimulatedGame game : aiArenaGames) {
            AssessmentSource source = classifyToSource(classifier, game, "PvP", targetTicks);
            total++;
            switch (source) {
                case DROOLS -> drools++;
                case ONNX -> onnx++;
                case LLM -> llm++;
            }
            if (source == AssessmentSource.DROOLS) {
                var    snap = classifier.snapshotConfidences();
                double max  = snap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                if (max < 0.7) {fallback++;}
            }
        }

        return new TierCounts(drools, onnx, llm, fallback, total);
    }

    private AssessmentSource classifyToSource(CascadingPatternClassifier classifier,
                                              SimulatedGame game, String matchup, int targetTicks) {
        var sessionManager = new ScoutingSessionManager();
        var accumulator = new TemporalWindowAccumulator();
        var featureExtractor = new StrategyFeatureExtractor();
        Point2d ourNexus = new Point2d(30, 30);
        Point2d enemyBase = new Point2d(120, 120);
        Race enemyRace = enemyRaceFromMatchup(matchup);

        classifier.reset();
        game.reset();
        long prevFrame = -1;
        CascadeResult lastResult = null;

        for (int tick = 0; tick < targetTicks; tick++) {
            game.tick();
            GameState state = game.snapshot();
            long gameTimeMs = (long) (state.gameFrame() * (1000.0 / FRAMES_PER_SECOND));
            double gameTimeMin = gameTimeMs / 60000.0;

            sessionManager.processFrame(state.enemyUnits(), gameTimeMs, ourNexus, enemyBase);
            sessionManager.evict(gameTimeMs);

            PatternClassificationRuleUnit patternData = sessionManager.buildPatternRuleUnit(gameTimeMin);
            taxonomy.activeSignatures(gameTimeMin).forEach(patternData.getSignatureStore()::add);
            try (RuleUnitInstance<PatternClassificationRuleUnit> instance = ruleUnit.createInstance(patternData)) {
                instance.fire();
            }

            accumulator.addSnapshot(DroolsScoutingTask.buildSnapshot(state));
            var features = featureExtractor.extract(accumulator.getWindowedFeatures(), MapCharacteristics.DEFAULT);
            lastResult = classifier.classify(
                    patternData.getEvidence(), patternData.getRevisions(),
                    features, enemyRace, state.gameFrame(), prevFrame, null);
            prevFrame = state.gameFrame();
        }

        if (lastResult == null || lastResult.assessments().isEmpty()) return AssessmentSource.DROOLS;
        return lastResult.assessments().get(0).source();
    }

    private static void appendAccuracyTable(StringBuilder report,
                                             Map<Integer, List<ClassificationResult>> resultsByMinute,
                                             String label) {
        Map<String, Map<Integer, int[]>> matchupAcc = new LinkedHashMap<>();
        for (String m : List.of("PvT", "PvZ", "PvP")) {
            matchupAcc.put(m, new LinkedHashMap<>());
            for (int min : CHECKPOINT_MINUTES) {
                matchupAcc.get(m).put(min, new int[]{0, 0});
            }
        }

        for (int min : CHECKPOINT_MINUTES) {
            for (ClassificationResult r : resultsByMinute.get(min)) {
                int[] counts = matchupAcc.getOrDefault(r.matchup(), new LinkedHashMap<>()).get(min);
                if (counts != null) {
                    counts[1]++;
                    if (r.correct()) counts[0]++;
                }
            }
        }

        report.append(String.format("  %s:%n", label));
        report.append(String.format("  %-6s", "Min"));
        for (String m : matchupAcc.keySet()) {
            report.append(String.format("  %-12s", m));
        }
        report.append("  Overall  Samples\n");

        for (int min : CHECKPOINT_MINUTES) {
            report.append(String.format("  %-6d", min));
            int allCorrect = 0, allTotal = 0;
            for (String m : matchupAcc.keySet()) {
                int[] counts = matchupAcc.get(m).get(min);
                allCorrect += counts[0];
                allTotal += counts[1];
                if (counts[1] > 0) {
                    report.append(String.format("  %d/%d=%.0f%%    ", counts[0], counts[1],
                            (double) counts[0] / counts[1] * 100));
                } else {
                    report.append(String.format("  %-12s", "—"));
                }
            }
            if (allTotal > 0) {
                report.append(String.format("  %.0f%%     %d", (double) allCorrect / allTotal * 100, allTotal));
            }
            report.append("\n");
        }
    }

    private static void appendComparisonBaselines(StringBuilder report,
                                                   ModeResults droolsOnly,
                                                   ModeResults onnxOnly,
                                                   ModeResults cascadeResults) {
        report.append("=== Comparison Baselines (IEM10, minute 4) ===\n");
        double droolsAcc = overallAccuracyAtMinute(droolsOnly.iem10(), 4);
        double onnxAcc = overallAccuracyAtMinute(onnxOnly.iem10(), 4);
        double cascadeAcc = overallAccuracyAtMinute(cascadeResults.iem10(), 4);
        report.append(String.format("  Drools-only: %.0f%%%n", droolsAcc * 100));
        report.append(String.format("  ONNX-only:   %.0f%%%n", onnxAcc * 100));
        report.append(String.format("  Cascade:     %.0f%%%n", cascadeAcc * 100));
        report.append(String.format("  Δ Cascade vs Drools-only: %+.0f%%%n", (cascadeAcc - droolsAcc) * 100));
        report.append("\n");

        if (cascadeResults.tierHits() != null && cascadeResults.tierHits().containsKey(4)) {
            TierCounts tc = cascadeResults.tierHits().get(4);
            report.append("=== Tier Hit Rates (cascade mode, minute 4) ===\n");
            report.append(String.format("  Drools resolved:  %.0f%% (%d/%d)%n",
                    tc.total() > 0 ? (double) tc.drools() / tc.total() * 100 : 0, tc.drools(), tc.total()));
            report.append(String.format("  ONNX resolved:    %.0f%% (%d/%d)%n",
                    tc.total() > 0 ? (double) tc.onnx() / tc.total() * 100 : 0, tc.onnx(), tc.total()));
            report.append(String.format("  LLM triggered:    %.0f%% (%d/%d)%n",
                    tc.total() > 0 ? (double) tc.llm() / tc.total() * 100 : 0, tc.llm(), tc.total()));
            report.append(String.format("  Default fallback: %.0f%% (%d/%d)%n",
                    tc.total() > 0 ? (double) tc.fallback() / tc.total() * 100 : 0, tc.fallback(), tc.total()));
            report.append("\n");
        }
    }

    private static double rushAccuracyAtMinute(Map<Integer, List<ClassificationResult>> results, int minute) {
        List<ClassificationResult> atMinute = results.get(minute);
        if (atMinute == null) return 1.0;
        long rushTotal = atMinute.stream().filter(ClassificationResult::isRush).count();
        long rushCorrect = atMinute.stream().filter(r -> r.isRush() && r.correct()).count();
        return rushTotal > 0 ? (double) rushCorrect / rushTotal : 1.0;
    }

    private static double overallAccuracyAtMinute(Map<Integer, List<ClassificationResult>> results, int minute) {
        List<ClassificationResult> atMinute = results.get(minute);
        if (atMinute == null || atMinute.isEmpty()) return 0.0;
        long correct = atMinute.stream().filter(ClassificationResult::correct).count();
        return (double) correct / atMinute.size();
    }

    private static int countLabelledAtMinute(Map<Integer, List<ClassificationResult>> results, int minute) {
        List<ClassificationResult> atMinute = results.get(minute);
        return atMinute == null ? 0 : atMinute.size();
    }
}
