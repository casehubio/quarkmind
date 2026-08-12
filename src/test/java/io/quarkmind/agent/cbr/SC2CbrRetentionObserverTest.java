package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.quarkmind.agent.MultiFactorDominanceAssessor;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.DominanceScore;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.summarisation.MomentBroker;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.summarisation.GameArc;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkmind.sc2.GameStarted;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SC2CbrRetentionObserverTest {

    CbrCaseMemoryStore store;
    SummarisationLifecycle summarisationLifecycle;
    MomentBroker momentBroker;
    MultiFactorDominanceAssessor dominanceAssessor;
    SC2CbrRetentionObserver observer;

    @BeforeEach
    void setUp() {
        store = mock(CbrCaseMemoryStore.class);
        when(store.store(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("stored-case-1");
        summarisationLifecycle = mock(SummarisationLifecycle.class);
        momentBroker = mock(MomentBroker.class);
        dominanceAssessor = mock(MultiFactorDominanceAssessor.class);
        when(dominanceAssessor.assess(any())).thenReturn(new DominanceScore(0.0, Map.of()));
        observer = new SC2CbrRetentionObserver(store, summarisationLifecycle, momentBroker, dominanceAssessor);
    }

    @Test
    void win_storesCase_andRecordsOutcome() {
        CaseOutcomeEvent event = buildEvent("WIN",
                Map.of(
                        QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.early-pressure",
                        QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                        QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.85));

        observer.onOutcome(event);

        verify(store).store(
                argThat(c -> c.problem().contains("ZERG_ROACH_RUSH")
                        && "strategy.early-pressure".equals(c.solution())
                        && "WIN".equals(c.outcome())),
                any(), any(), any(), any(), any(), any());
        verify(store).recordOutcome(eq("stored-case-1"), eq(SC2GameCbrCase.CBR_TYPE),
                argThat(o -> o.successRate() == 1.0));
    }

    @Test
    void loss_storesWithZeroSuccessRate() {
        CaseOutcomeEvent event = buildEvent("LOSS",
                Map.of(
                        QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools",
                        QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "TERRAN_MARINE_RUSH",
                        QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.7));

        observer.onOutcome(event);

        verify(store).recordOutcome(any(), any(),
                argThat(o -> o.successRate() == 0.0));
    }

    @Test
    void tie_storesWithHalfSuccessRate() {
        CaseOutcomeEvent event = buildEvent("TIE",
                Map.of(
                        QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools",
                        QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_MACRO",
                        QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.9));

        observer.onOutcome(event);

        verify(store).recordOutcome(any(), any(),
                argThat(o -> o.successRate() == 0.5));
    }

    @Test
    void unknown_skips() {
        CaseOutcomeEvent event = buildEvent("UNKNOWN", Map.of());
        observer.onOutcome(event);
        verifyNoInteractions(store);
    }

    @Test
    void missingArchetype_skips() {
        CaseOutcomeEvent event = buildEvent("WIN",
                Map.of(QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools"));
        observer.onOutcome(event);
        verifyNoInteractions(store);
    }

    @Test
    void featureExtraction_derivesRaceAndMatchup() {
        CaseOutcomeEvent event = buildEvent("WIN",
                Map.of(
                        QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools",
                        QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "TERRAN_BIO_TIMING",
                        QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.75));

        observer.onOutcome(event);

        verify(store).store(
                argThat(c -> {
                    var features = c.features();
                    return "TERRAN".equals(features.get("enemy_race").toRawValue())
                            && "PvT".equals(features.get("matchup").toRawValue());
                }),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void onOutcome_buildsEnrichedCaseWithAccumulatedData() {
        // Accumulate moments
        observer.collectMoment(new LevelEvent<>(
                new GameMoment(GameMomentType.FIRST_CONTACT, 2800, Map.of()),
                2800, new EventLevel("moment", 2)));
        observer.collectMoment(new LevelEvent<>(
                new GameMoment(GameMomentType.BATTLE_STARTED, 5000, Map.of()),
                5000, new EventLevel("moment", 2)));
        observer.collectMoment(new LevelEvent<>(
                new GameMoment(GameMomentType.SUPPLY_BLOCK, 3000, Map.of()),
                3000, new EventLevel("moment", 2)));
        // Accumulate phases
        observer.collectPhase(new LevelEvent<>(
                new TacticalPosture("EARLY_MACRO", 0, "no combat"),
                0, new EventLevel("phase", 3)));
        observer.collectPhase(new LevelEvent<>(
                new TacticalPosture("MID_SKIRMISH", 5000, "combat"),
                5000, new EventLevel("phase", 3)));
        // Accumulate arc
        observer.collectArc(new LevelEvent<>(
                new GameArc("Game progression: EARLY_MACRO -> MID_SKIRMISH", 5000),
                5000, new EventLevel("arc", 4)));

        // Build game state with 2 nexus, 30 workers
        GameState gameState = new GameState(
                500, 200, 46, 44,
                buildWorkers(30), buildBases(2),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                8000L, null);

        when(dominanceAssessor.assess(gameState))
                .thenReturn(new DominanceScore(0.3, Map.of("economy", 0.5, "army", 0.4)));

        CaseOutcomeEvent event = buildEvent("WIN", Map.of(
                QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.early-pressure",
                QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.85,
                QuarkMindCaseFile.GAME_STATE, gameState,
                QuarkMindCaseFile.OPPONENT_ID, "ZERG_ROACH_RUSH",
                QuarkMindCaseFile.SCOUTING_DISPATCH_FRAME, 1500L));

        observer.onOutcome(event);

        verify(store).store(
                argThat(c -> {
                    var f = c.features();
                    return f.containsKey("phase_sequence")
                           && f.containsKey("moment_count")
                           && f.containsKey("battle_count")
                           && f.containsKey("supply_block_count")
                           && f.containsKey("dominance_overall")
                           && f.containsKey("expansion_count")
                           && f.containsKey("worker_count_final")
                           && f.containsKey("opponent_id")
                           && f.containsKey("first_contact_minute")
                           && f.containsKey("scout_dispatch_minute")
                           && f.containsKey("arc_narrative");
                }),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void gameStarted_clearsAccumulators() {
        observer.collectMoment(new LevelEvent<>(
                new GameMoment(GameMomentType.BATTLE_STARTED, 100, Map.of()),
                100, new EventLevel("moment", 2)));
        observer.collectPhase(new LevelEvent<>(
                new TacticalPosture("MID_SKIRMISH", 100, "test"),
                100, new EventLevel("phase", 3)));
        observer.collectArc(new LevelEvent<>(
                new GameArc("narrative", 100),
                100, new EventLevel("arc", 4)));

        observer.onGameStarted(new GameStarted());

        assertThat(observer.moments()).isEmpty();
        assertThat(observer.phases()).isEmpty();
        assertThat(observer.latestArc()).isNull();
    }

    @Test
    void onOutcome_includesConvergenceFeatures_whenBothKeysPresent() {
        var assessments = List.of(
                new PatternAssessment(StrategyArchetype.ZERG_ROACH_RUSH, 0.85, 8000, "final"));

        CaseOutcomeEvent event = buildEvent("WIN", Map.of(
                QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.early-pressure",
                QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.85,
                QuarkMindCaseFile.STRATEGY_INITIAL_ARCHETYPE, "ZERG_ROACH_RUSH",
                QuarkMindCaseFile.SCOUTING_FINAL_ASSESSMENT, assessments));

        observer.onOutcome(event);

        verify(store).store(
                argThat(c -> {
                    var f = c.features();
                    return f.containsKey("scouting_convergence")
                           && ((Number) f.get("scouting_convergence").toRawValue()).doubleValue() == 1.0
                           && ((Number) f.get("assessment_stable").toRawValue()).doubleValue() == 1.0;
                }),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void onOutcome_defaultsConvergenceToZero_whenInitialArchetypeMissing() {
        CaseOutcomeEvent event = buildEvent("WIN", Map.of(
                QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools",
                QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.7));

        observer.onOutcome(event);

        verify(store).store(
                argThat(c -> {
                    var f = c.features();
                    return f.containsKey("scouting_convergence")
                           && ((Number) f.get("scouting_convergence").toRawValue()).doubleValue() == 0.0
                           && ((Number) f.get("assessment_stable").toRawValue()).doubleValue() == 0.0;
                }),
                any(), any(), any(), any(), any(), any());
    }

    private static List<Unit> buildWorkers(int count) {
        var workers = new java.util.ArrayList<Unit>();
        for (int i = 0; i < count; i++) {
            workers.add(new Unit("w" + i, UnitType.PROBE,
                                 new Point2d(10 + i, 10), 20, 20, 20, 20, 0, 0));
        }
        return workers;
    }

    private static List<Building> buildBases(int count) {
        var bases = new java.util.ArrayList<Building>();
        for (int i = 0; i < count; i++) {
            bases.add(new Building("b" + i, BuildingType.NEXUS,
                                   new Point2d(20 + i * 10, 20), 1000, 1000, true));
        }
        return bases;
    }


    private CaseOutcomeEvent buildEvent(String outcomeLabel, Map<String, Object> snapshot) {
        return new CaseOutcomeEvent(
                "starcraft-game", "tenant-1", UUID.randomUUID(),
                snapshot, outcomeLabel, Instant.now(), Map.of());
    }
}
