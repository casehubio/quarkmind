package io.quarkmind.plugin.scouting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Unit;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND;

@QuarkusTest
@Tag("diagnostic")
class PipelineFixtureCapture {

    static final int SAMPLE_INTERVAL_FRAMES = 336;

    @Inject RuleUnit<PatternClassificationRuleUnit> patternRuleUnit;
    @Inject StrategyTaxonomy taxonomy;

    @Test
    void captureFixtures() throws Exception {
        var replayFile = new File("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
        if (!replayFile.exists()) return;

        var game = new ReplaySimulatedGame(replayFile.toPath(), 1);
        var sessionManager = new ScoutingSessionManager();
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        List<Map<String, Object>> l1Inputs = new ArrayList<>();
        List<Map<String, Object>> l1Outputs = new ArrayList<>();
        List<Map<String, Object>> l2Outputs = new ArrayList<>();

        long prevFrame = -1;
        while (!game.isComplete()) {
            game.tick();
            GameState state = game.snapshot();
            long frame = game.currentLoop();
            double gameTimeMin = frame / (GAME_LOOPS_PER_SECOND * 60.0);

            List<Unit> enemies = state.enemyUnits();
            List<Building> buildings = state.myBuildings();
            Point2d ourBase = buildings.isEmpty() ? new Point2d(30, 30)
                : buildings.get(0).position();
            Point2d estimatedBase = DroolsScoutingTask.estimatedEnemyBase(ourBase, 256);

            long gameTimeMs = (long) (gameTimeMin * 60_000);
            sessionManager.processFrame(enemies, gameTimeMs, ourBase, estimatedBase);
            sessionManager.evict(gameTimeMs);

            if (frame % SAMPLE_INTERVAL_FRAMES != 0 && frame > 0) {
                prevFrame = frame;
                continue;
            }

            PatternClassificationRuleUnit patternData = sessionManager.buildPatternRuleUnit(gameTimeMin);
            taxonomy.activeSignatures(gameTimeMin).forEach(patternData.getSignatureStore()::add);
            try (RuleUnitInstance<PatternClassificationRuleUnit> instance =
                    patternRuleUnit.createInstance(patternData)) {
                instance.fire();
            }

            Race enemyRace = enemies.stream()
                .map(u -> u.type().race())
                .findFirst().orElse(null);

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("frame", frame);
            input.put("gameTimeMinutes", gameTimeMin);
            input.put("enemyCount", enemies.size());
            input.put("enemyRace", enemyRace != null ? enemyRace.name() : null);
            l1Inputs.add(input);

            Map<String, Object> l1Out = new LinkedHashMap<>();
            l1Out.put("frame", frame);
            l1Out.put("evidenceMarkers", patternData.getEvidence().stream()
                .map(e -> Map.of("archetype", e.archetype().name(),
                    "weight", e.weight(), "signal", e.signal()))
                .toList());
            l1Out.put("revisions", patternData.getRevisions().stream()
                .map(r -> Map.of("archetype", r.archetype().name(),
                    "dampingFactor", r.dampingFactor(), "reason", r.reason()))
                .toList());
            l1Outputs.add(l1Out);

            CascadeResult cascadeResult = classifier.classify(
                patternData.getEvidence(), patternData.getRevisions(),
                null, enemyRace, frame, prevFrame, null, enemies.size());
            Map<String, Object> l2Out = new LinkedHashMap<>();
            l2Out.put("frame", frame);
            l2Out.put("assessments", cascadeResult.assessments().stream()
                .map(a -> Map.of("archetype", a.archetype().name(),
                    "confidence", a.confidence(), "source", a.source().name()))
                .toList());
            l2Out.put("llmTriggered", cascadeResult.llmTriggered());
            l2Outputs.add(l2Out);

            prevFrame = frame;
        }

        var outDir = new File("src/test/resources/fixtures/pipeline/nothing-4720936");
        outDir.mkdirs();
        mapper.writeValue(new File(outDir, "l1-input.json"), l1Inputs);
        mapper.writeValue(new File(outDir, "l1-output.json"), l1Outputs);
        mapper.writeValue(new File(outDir, "l2-output.json"), l2Outputs);
        mapper.writeValue(new File(outDir, "metadata.json"), Map.of(
            "replay", "Nothing_4720936.SC2Replay",
            "player", 1,
            "matchup", "PvZ",
            "sampledFrames", l1Inputs.size(),
            "sampleInterval", SAMPLE_INTERVAL_FRAMES));

        System.out.printf("[FIXTURE] Captured %d frames to %s%n", l1Inputs.size(), outDir);
        long framesWithEvidence = l1Outputs.stream()
            .filter(o -> !((List<?>) o.get("evidenceMarkers")).isEmpty()).count();
        long framesWithAssessments = l2Outputs.stream()
            .filter(o -> !((List<?>) o.get("assessments")).isEmpty()).count();
        System.out.printf("[FIXTURE] Frames with L1 evidence: %d%n", framesWithEvidence);
        System.out.printf("[FIXTURE] Frames with L2 assessments: %d%n", framesWithAssessments);
    }
}
