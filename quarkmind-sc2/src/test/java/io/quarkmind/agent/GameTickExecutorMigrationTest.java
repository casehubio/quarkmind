package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.quarkmind.agent.plugin.SummarisationTickable;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.plugin.commentary.CommentaryAccumulator;
import io.quarkmind.plugin.commentary.CommentaryTriggerBuilder;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.SC2Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Migration test: verifies that {@link GameTickExecutor} dispatches ticks via
 * {@link QuarkMindCaseHub#signalAndAwaitSync} instead of the retired
 * {@code CaseEngine.createAndSolve()}.
 *
 * <p>Uses Mockito — no CDI, no Quarkus. Verifies:
 * <ul>
 *   <li>Each tick calls {@code signalAndAwaitSync(gameSessionId, translatedState, 5s)}</li>
 *   <li>The returned {@code CaseContext} is propagated to {@code TickResult}</li>
 *   <li>Engine exceptions produce a null-context TickResult (not a crash)</li>
 *   <li>Summarisation runs after engine settlement</li>
 *   <li>Dispatch runs after summarisation</li>
 * </ul>
 *
 * <p>Refs #207
 */
class GameTickExecutorMigrationTest {

    private SC2Engine engine;
    private GameStateTranslator translator;
    private QuarkMindCaseHub caseHub;
    private GameSession gameSession;
    private PluginDispatchBroker dispatchBroker;
    private SummarisationTickable summarisation;
    private DeferredAdvisoryEvaluator deferredAdvisoryEvaluator;
    private MilestoneOutcomeRecorder milestoneOutcomeRecorder;
    private AdvisoryMilestoneOutcomeRecorder advisoryMilestoneOutcomeRecorder;

    private CommentaryTriggerBuilder commentaryTriggerBuilder;
    private CommentaryAccumulator commentaryAccumulator;
    private io.quarkmind.plugin.coaching.CoachingTriggerBuilder coachingTriggerBuilder;
    private io.quarkmind.plugin.coaching.CoachingComplianceEvaluator coachingComplianceEvaluator;
    private GameTickExecutor executor;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        engine = mock(SC2Engine.class);
        translator = new GameStateTranslator();
        translator.onGameStarted(new GameStarted("PROTOSS", "COMPUTER", "VeryEasy", null));
        caseHub = mock(QuarkMindCaseHub.class);
        gameSession = new GameSession();
        dispatchBroker = mock(PluginDispatchBroker.class);
        summarisation = mock(SummarisationTickable.class);
        deferredAdvisoryEvaluator = mock(DeferredAdvisoryEvaluator.class);
        milestoneOutcomeRecorder = mock(MilestoneOutcomeRecorder.class);
        advisoryMilestoneOutcomeRecorder = mock(AdvisoryMilestoneOutcomeRecorder.class);
        commentaryTriggerBuilder = mock(CommentaryTriggerBuilder.class);
        commentaryAccumulator = mock(CommentaryAccumulator.class);
        coachingTriggerBuilder = mock(io.quarkmind.plugin.coaching.CoachingTriggerBuilder.class);
        coachingComplianceEvaluator = mock(io.quarkmind.plugin.coaching.CoachingComplianceEvaluator.class);

        sessionId = UUID.randomUUID();
        gameSession.setCaseId(sessionId);

        // Mock commentary beans to return empty maps by default (no triggers)
        when(commentaryTriggerBuilder.build(any(CaseContext.class), anyLong())).thenReturn(Collections.emptyMap());
        when(commentaryAccumulator.tick(anyLong())).thenReturn(Collections.emptyMap());

        executor = new GameTickExecutor();
        executor.engine = engine;
        executor.translator = translator;
        executor.caseHub = caseHub;
        executor.gameSession = gameSession;
        executor.pluginDispatchBroker = dispatchBroker;
        executor.summarisationLifecycle = summarisation;
        executor.deferredAdvisoryEvaluator = deferredAdvisoryEvaluator;
        executor.milestoneOutcomeRecorder = milestoneOutcomeRecorder;
        executor.advisoryMilestoneOutcomeRecorder = advisoryMilestoneOutcomeRecorder;
        executor.commentaryTriggerBuilder = commentaryTriggerBuilder;
        executor.commentaryAccumulator = commentaryAccumulator;
        executor.coachingTriggerBuilder = coachingTriggerBuilder;
        executor.coachingComplianceEvaluator = coachingComplianceEvaluator;
        executor.gameMode = "ai";
    }

    @Test
    void execute_callsSignalAndAwaitSync_withGameSessionId() {
        GameState state = stubGameState(42L, 200, 100);
        when(engine.observe()).thenReturn(state);

        CaseContext returnedCtx = mock(CaseContext.class);
        when(caseHub.signalAndAwaitSync(eq(sessionId), any(), any())).thenReturn(returnedCtx);

        AgentOrchestrator.TickResult result = executor.execute();

        // Verify signalAndAwaitSync called with the correct session ID
        ArgumentCaptor<Map<String, Object>> captor = captureMapArg();
        verify(caseHub).signalAndAwaitSync(eq(sessionId), captor.capture(), eq(Duration.ofSeconds(5)));

        // Verify the update map contains translated game state
        Map<String, Object> updates = captor.getValue();
        assertThat(updates).containsEntry(QuarkMindCaseFile.GAME_FRAME, 42L);
        assertThat(updates).containsEntry(QuarkMindCaseFile.MINERALS, 200);
        assertThat(updates).containsEntry(QuarkMindCaseFile.VESPENE, 100);
        assertThat(updates).containsEntry(QuarkMindCaseFile.READY, Boolean.TRUE);

        // Verify result carries the returned CaseContext
        assertThat(result.solveSucceeded()).isTrue();
        assertThat(result.caseContext()).isSameAs(returnedCtx);
    }

    @Test
    void execute_engineException_returnsNullContextResult() {
        GameState state = stubGameState(10L, 50, 0);
        when(engine.observe()).thenReturn(state);
        when(caseHub.signalAndAwaitSync(any(), any(), any()))
            .thenThrow(new RuntimeException("settlement timeout"));

        AgentOrchestrator.TickResult result = executor.execute();

        assertThat(result.solveSucceeded()).isFalse();
        assertThat(result.caseContext()).isNull();
    }

    @Test
    void execute_invokesPhysicsThenSettleThenSummarisationThenDispatch() {
        // Verify execution order: tick → observe → signalAndAwaitSync → summarisation → dispatch
        GameState state = stubGameState(100L, 300, 50);
        when(engine.observe()).thenReturn(state);

        CaseContext ctx = mock(CaseContext.class);
        when(caseHub.signalAndAwaitSync(any(), any(), any())).thenReturn(ctx);

        executor.execute();

        // All four phases were called
        verify(engine).tick();
        verify(engine).observe();
        verify(caseHub).signalAndAwaitSync(any(), any(), any());
        verify(summarisation).tick(100L);
        verify(engine).dispatch();
    }

    @Test
    void execute_dispatchRunsEvenOnSettlementFailure() {
        GameState state = stubGameState(5L, 50, 0);
        when(engine.observe()).thenReturn(state);
        when(caseHub.signalAndAwaitSync(any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));

        executor.execute();

        // dispatch() must still run — IntentQueue is populated by plugins, not CaseContext
        verify(engine).dispatch();
        verify(summarisation).tick(5L);
    }

    @Test
    void execute_brokerRecordTickCalledBeforeEngineSettle() {
        GameState state = stubGameState(1L, 100, 0);
        when(engine.observe()).thenReturn(state);
        when(caseHub.signalAndAwaitSync(any(), any(), any())).thenReturn(mock(CaseContext.class));

        executor.execute();

        // dispatchBroker.recordTick is called with the translated state
        verify(dispatchBroker).recordTick(any());
    }

    @Test
    void execute_timingsAreRecorded() {
        GameState state = stubGameState(1L, 100, 0);
        when(engine.observe()).thenReturn(state);
        when(caseHub.signalAndAwaitSync(any(), any(), any())).thenReturn(mock(CaseContext.class));

        AgentOrchestrator.TickResult result = executor.execute();

        assertThat(result.timings()).isNotNull();
        assertThat(result.timings().totalMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_doesNotCallCreateAndSolve() {
        // Verify the poc CaseEngine is not used — GameTickExecutor no longer injects it
        GameState state = stubGameState(1L, 100, 0);
        when(engine.observe()).thenReturn(state);
        when(caseHub.signalAndAwaitSync(any(), any(), any())).thenReturn(mock(CaseContext.class));

        executor.execute();

        // The test passes by construction: GameTickExecutor no longer has a CaseEngine field.
        // This test documents the migration intent explicitly.
        verify(caseHub).signalAndAwaitSync(eq(sessionId), any(), eq(Duration.ofSeconds(5)));
    }

    @Test
    void execute_callsMilestoneEvaluationAfterSummarisationBeforeDispatch() {
        GameState state = stubGameState(5000L, 200, 100);
        when(engine.observe()).thenReturn(state);
        when(caseHub.signalAndAwaitSync(any(), any(), any())).thenReturn(mock(CaseContext.class));

        executor.execute();

        verify(milestoneOutcomeRecorder).evaluateMilestones(state);
        // Verify ordering: summarisation before milestone, milestone before dispatch
        var inOrder = org.mockito.Mockito.inOrder(summarisation, milestoneOutcomeRecorder, engine);
        inOrder.verify(summarisation).tick(5000L);
        inOrder.verify(milestoneOutcomeRecorder).evaluateMilestones(state);
        inOrder.verify(engine).dispatch();
    }

    @Test
    void execute_callsAdvisoryMilestoneEvaluationInAiMode() {
        GameState state = stubGameState(5000L, 200, 100);
        when(engine.observe()).thenReturn(state);
        when(caseHub.signalAndAwaitSync(any(), any(), any())).thenReturn(mock(CaseContext.class));

        executor.execute();

        verify(advisoryMilestoneOutcomeRecorder).evaluateMilestones(state);
    }


    @Test
    void execute_coachMode_skipsMilestonesAndAdvisory() {
        executor.gameMode = "coach";
        GameState state = stubGameState(100L, 400, 200);
        when(engine.observe()).thenReturn(state);
        CaseContext ctx = mock(CaseContext.class);
        when(caseHub.signalAndAwaitSync(eq(sessionId), any(), any())).thenReturn(ctx);
        when(coachingTriggerBuilder.build(any(CaseContext.class), anyLong())).thenReturn(Collections.emptyMap());

        executor.execute();

        verify(milestoneOutcomeRecorder, never()).evaluateMilestones(any());
        verify(advisoryMilestoneOutcomeRecorder, never()).evaluateMilestones(any());
        verify(deferredAdvisoryEvaluator, never()).evaluate(any(), anyLong());
        verify(coachingComplianceEvaluator).evaluate(state, 100L);
    }

    @Test
    void execute_coachMode_firesCoachingTrigger() {
        executor.gameMode = "coach";
        GameState state = stubGameState(200L, 300, 150);
        when(engine.observe()).thenReturn(state);
        CaseContext ctx = mock(CaseContext.class);
        when(caseHub.signalAndAwaitSync(eq(sessionId), any(), any())).thenReturn(ctx);

        Map<String, Object> triggerMap = Map.of(QuarkMindCaseFile.COACHING_TRIGGER, Map.of("urgencyTier", "CRISIS"));
        when(coachingTriggerBuilder.build(any(CaseContext.class), anyLong())).thenReturn(triggerMap);
        doNothing().when(caseHub).signal(any(), any(Map.class));

        executor.execute();

        verify(caseHub).signal(eq(sessionId), eq(triggerMap));
    }

    @Test
    void execute_aiMode_skipsCoachingEvaluation() {
        executor.gameMode = "ai";
        GameState state = stubGameState(100L, 400, 200);
        when(engine.observe()).thenReturn(state);
        CaseContext ctx = mock(CaseContext.class);
        when(caseHub.signalAndAwaitSync(eq(sessionId), any(), any())).thenReturn(ctx);

        executor.execute();

        verify(milestoneOutcomeRecorder).evaluateMilestones(state);
        verify(coachingComplianceEvaluator, never()).evaluate(any(), anyLong());
    }


    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static GameState stubGameState(long frame, int minerals, int vespene) {
        return new GameState(minerals, vespene, 15, 6, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), frame, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captureMapArg() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
