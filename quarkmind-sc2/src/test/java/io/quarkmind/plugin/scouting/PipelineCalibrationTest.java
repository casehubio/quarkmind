package io.quarkmind.plugin.scouting;

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
import java.util.List;

import static io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("diagnostic")
class PipelineCalibrationTest {

    @Inject RuleUnit<PatternClassificationRuleUnit> ruleUnit;
    @Inject StrategyTaxonomy taxonomy;

    @Test
    void calibration_cascadeNeverEmptyAfterEnemiesVisible() throws Exception {
        var replayFile = new File("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
        if (!replayFile.exists()) return;

        var game = new ReplaySimulatedGame(replayFile.toPath(), 1);
        var sessionManager = new ScoutingSessionManager();
        var classifier = new CascadingPatternClassifier(0.7, 0.5);

        long prevFrame = -1;
        boolean enemiesEverSeen = false;
        int ticksWithEnemies = 0;
        int ticksWithRealClassification = 0;
        int ticksWithFallback = 0;
        int ticksEmpty = 0;

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

            PatternClassificationRuleUnit patternData = sessionManager.buildPatternRuleUnit(gameTimeMin);
            taxonomy.activeSignatures(gameTimeMin).forEach(patternData.getSignatureStore()::add);
            try (RuleUnitInstance<PatternClassificationRuleUnit> instance =
                    ruleUnit.createInstance(patternData)) {
                instance.fire();
            }

            Race enemyRace = enemies.stream()
                .map(u -> u.type().race())
                .findFirst().orElse(null);
            CascadeResult result = classifier.classify(
                patternData.getEvidence(), patternData.getRevisions(),
                null, enemyRace, frame, prevFrame, null, enemies.size());

            if (!enemies.isEmpty()) {
                enemiesEverSeen = true;
                ticksWithEnemies++;

                if (result.assessments().isEmpty()) {
                    ticksEmpty++;
                } else {
                    var top = result.assessments().get(0);
                    if (top.archetype().name().endsWith("_COMPOSITION_UNKNOWN")) {
                        ticksWithFallback++;
                    } else {
                        ticksWithRealClassification++;
                    }
                }
            }
            prevFrame = frame;
        }

        assertThat(enemiesEverSeen).as("Replay should contain enemy units").isTrue();

        assertThat(ticksEmpty)
            .as("Ticks with enemies but empty assessments")
            .isZero();

        if (ticksWithEnemies > 0) {
            double fallbackRate = (double) ticksWithFallback / ticksWithEnemies;
            System.out.printf("[CALIBRATION] Ticks with enemies: %d%n", ticksWithEnemies);
            System.out.printf("[CALIBRATION] Real classifications: %d (%.1f%%)%n",
                ticksWithRealClassification, 100.0 * ticksWithRealClassification / ticksWithEnemies);
            System.out.printf("[CALIBRATION] Fallback (UNKNOWN): %d (%.1f%%)%n",
                ticksWithFallback, 100.0 * fallbackRate);
            System.out.printf("[CALIBRATION] Empty (BUG): %d%n", ticksEmpty);
        }
    }
}
