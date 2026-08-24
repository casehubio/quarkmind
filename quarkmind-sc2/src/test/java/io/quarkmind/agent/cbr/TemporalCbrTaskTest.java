package io.quarkmind.agent.cbr;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkmind.agency.context.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TemporalPrediction;
import io.quarkmind.domain.TimelineObservation;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class TemporalCbrTaskTest {

    CbrCaseMemoryStore cbrStore;
    TimelineSampler timelineSampler;
    SummarisationLifecycle summarisationLifecycle;
    TemporalCbrTask task;

    @BeforeEach
    void setUp() {
        cbrStore = mock(CbrCaseMemoryStore.class);
        timelineSampler = new TimelineSampler();
        summarisationLifecycle = mock(SummarisationLifecycle.class);
        task = new TemporalCbrTask(cbrStore, timelineSampler, summarisationLifecycle);
    }

    @Test
    void getId() {
        assertThat(task.getId()).isEqualTo("temporal-cbr.predict");
    }

    @Test
    void produces_declaresAllKeys() {
        var produced = task.produces();
        assertThat(produced).contains(
                QuarkMindCaseFile.TEMPORAL_PREDICTION,
                QuarkMindCaseFile.TEMPORAL_SIMILAR_COUNT,
                QuarkMindCaseFile.TEMPORAL_SIMILAR_BEST_SCORE);
    }

    @Test
    void requires_declaresGameStateAndArchetype() {
        var required = task.requires();
        assertThat(required).contains(
                QuarkMindCaseFile.GAME_STATE,
                QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE);
    }

    @Test
    void activateIf_requiresMinimumTimelineSize() {
        var ctx = new MutableMapCaseContext(Map.of());
        assertThat(task.activateIf().test(ctx)).isFalse();

        feedSampler(3);
        assertThat(task.activateIf().test(ctx)).isFalse();

        feedSampler(1);
        assertThat(task.activateIf().test(ctx)).isTrue();
    }

    @Test
    void execute_emptyRetrievalResults_noPredictionWritten() {
        doReturn(List.of()).when(cbrStore).retrieveSimilar(any(), any());
        feedSampler(5);
        var ctx = buildContext(3360L);
        task.execute(ctx);
        assertThat(ctx.get(QuarkMindCaseFile.TEMPORAL_PREDICTION)).isNull();
    }

    @Test
    void execute_throttles_skipsIfTooSoon() {
        feedSampler(5);
        var storedCase = buildStoredCase(10);
        doReturn(List.of(storedCase)).when(cbrStore).retrieveSimilar(any(), any());

        var ctx1 = buildContext(0L);
        task.execute(ctx1);
        assertThat(ctx1.get(QuarkMindCaseFile.TEMPORAL_PREDICTION)).isNotNull();

        var ctx2 = buildContext(100L);
        task.execute(ctx2);
        assertThat(ctx2.get(QuarkMindCaseFile.TEMPORAL_PREDICTION)).isNull();

        var ctx3 = buildContext(3000L);
        task.execute(ctx3);
        assertThat(ctx3.get(QuarkMindCaseFile.TEMPORAL_PREDICTION)).isNotNull();
    }

    @Test
    void execute_writesPredictionToCaseFile() {
        feedSampler(5);
        var storedCase = buildStoredCase(10);
        doReturn(List.of(storedCase)).when(cbrStore).retrieveSimilar(any(), any());

        var ctx = buildContext(0L);
        task.execute(ctx);

        assertThat(ctx.get(QuarkMindCaseFile.TEMPORAL_PREDICTION))
                .isInstanceOf(TemporalPrediction.class);
        assertThat(ctx.get(QuarkMindCaseFile.TEMPORAL_SIMILAR_COUNT))
                .isEqualTo(1);
        assertThat((double) ctx.get(QuarkMindCaseFile.TEMPORAL_SIMILAR_BEST_SCORE))
                .isGreaterThan(0.0);
    }

    @Test
    void execute_clearsOnGameStarted() {
        feedSampler(5);
        var storedCase = buildStoredCase(10);
        doReturn(List.of(storedCase)).when(cbrStore).retrieveSimilar(any(), any());

        var ctx1 = buildContext(0L);
        task.execute(ctx1);
        assertThat(ctx1.get(QuarkMindCaseFile.TEMPORAL_PREDICTION)).isNotNull();

        task.onGameStarted(new GameStarted());
        timelineSampler.onGameStarted(new GameStarted());
        feedSampler(5);

        var ctx2 = buildContext(0L);
        task.execute(ctx2);
        assertThat(ctx2.get(QuarkMindCaseFile.TEMPORAL_PREDICTION)).isNotNull();
    }

    @Test
    void extractPrediction_directCall() {
        var queryTimeline = List.of(
                new TimelineObservation(0.0, 12, 50, 0),
                new TimelineObservation(0.5, 13, 100, 2),
                new TimelineObservation(1.0, 14, 150, 4),
                new TimelineObservation(1.5, 15, 200, 6),
                new TimelineObservation(2.0, 16, 250, 8));
        var storedCase = buildStoredCase(10);
        var results    = List.of(storedCase);

        var prediction = task.extractPrediction(queryTimeline, results);

        assertThat(prediction).isNotNull();
        assertThat(prediction.matchCount()).isEqualTo(1);
        assertThat(prediction.bestMatchScore()).isEqualTo(0.85);
    }


    private void feedSampler(int count) {
        int existingSize = timelineSampler.getTimeline().size();
        for (int i = 0; i < count; i++) {
            long frame = (existingSize + i) * 672L;
            int workers = 12 + i;
            var gs = new GameState(50 + i * 50, 0, workers + i * 2, workers + i * 2,
                    buildWorkers(workers), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    frame, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
            timelineSampler.tick(gs);
        }
    }

    private MutableMapCaseContext buildContext(long gameFrame) {
        var gs = new GameState(200, 0, 30, 30,
                buildWorkers(16), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                gameFrame, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
        return new MutableMapCaseContext(new HashMap<>(Map.of(
                QuarkMindCaseFile.GAME_STATE, gs,
                QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                QuarkMindCaseFile.ENEMY_RACE, "ZERG")));
    }

    private ScoredCbrCase<SC2GameCbrCase> buildStoredCase(int timelineSize) {
        var timeline = new java.util.ArrayList<Map<String, FeatureValue>>();
        for (int i = 0; i < timelineSize; i++) {
            timeline.add(Map.of(
                    "minute", FeatureValue.number(i * 0.5),
                    "our_workers", FeatureValue.number(12 + i),
                    "our_minerals", FeatureValue.number(50 + i * 50),
                    "our_army_supply", FeatureValue.number(i * 3)));
        }
        var features = new HashMap<String, FeatureValue>();
        features.put("enemy_archetype", FeatureValue.string("ZERG_ROACH_RUSH"));
        features.put("matchup", FeatureValue.string("PvZ"));
        features.put("timeline", FeatureValue.structList(timeline));
        features.put("phase_sequence", FeatureValue.stringList("EARLY_MACRO", "MID_SKIRMISH"));
        var cbrCase = new SC2GameCbrCase("vs ZERG_ROACH_RUSH (PvZ)", "strategy.drools",
                "WIN", 0.9, features);
        return new ScoredCbrCase<>(cbrCase, 0.85);
    }

    private static List<Unit> buildWorkers(int count) {
        var workers = new java.util.ArrayList<Unit>();
        for (int i = 0; i < count; i++) {
            workers.add(new Unit("w" + i, UnitType.PROBE,
                    new Point2d(10 + i, 10), 20, 20, 20, 20, 0, 0));
        }
        return workers;
    }
}
