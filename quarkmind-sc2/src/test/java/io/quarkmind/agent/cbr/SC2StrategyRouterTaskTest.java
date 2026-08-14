package io.quarkmind.agent.cbr;

import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agency.context.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.PatternAssessmentPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.PatternAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SC2StrategyRouterTaskTest {

    ScoutingIntelBroker broker;
    CbrCaseMemoryStore cbrStore;
    GameSession gameSession;
    SC2StrategyRouterTask router;

    @BeforeEach
    void setUp() {
        broker = mock(ScoutingIntelBroker.class);
        cbrStore = mock(CbrCaseMemoryStore.class);
        when(cbrStore.retrieveSimilar(any(), any())).thenReturn(List.of());
        gameSession = new GameSession();

        TrustCandidateClassifier classifier = new TrustCandidateClassifier();
        TrustScoreSource scoreSource = SC2ImplementationRoutingStrategyTest.stubScoreSource(Map.of());
        TrustRoutingPolicyProvider policyProvider = SC2ImplementationRoutingStrategyTest.stubPolicyProvider();

        List<StrategyTask> strategies = List.of(
                stubStrategy("strategy.drools"),
                stubStrategy("strategy.early-pressure"));

        router = new SC2StrategyRouterTask(
                broker, cbrStore, gameSession, strategies,
                classifier, scoreSource, policyProvider, 0.6, 1);
    }

    @Test
    void id() {
        assertThat(router.getId()).isEqualTo("strategy-routing.cbr");
    }

    @Test
    void firstTick_noArchetype_selectsFallback() {
        when(broker.current(ScoutingIntelType.PATTERN_ASSESSMENT)).thenReturn(Optional.empty());
        MutableMapCaseContext ctx = new MutableMapCaseContext(new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));

        router.execute(ctx);

        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID))
                .isEqualTo("strategy.drools");
    }

    @Test
    void archetypePresent_routesAndWritesKeys() {
        setArchetype(StrategyArchetype.ZERG_ROACH_RUSH, 0.85);
        MutableMapCaseContext ctx = new MutableMapCaseContext(new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));

        router.execute(ctx);

        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID)).isNotNull();
        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_ROUTED_CONTEXT)).isNotNull();
        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE))
                .isEqualTo("ZERG_ROACH_RUSH");
        assertThat(ctx.getInt(QuarkMindCaseFile.STRATEGY_PIVOT_COUNT))
                .isEqualTo(0);
    }

    @Test
    void sameArchetype_skipsReroute() {
        setArchetype(StrategyArchetype.ZERG_ROACH_RUSH, 0.85);
        Map<String, Object> persisted = new HashMap<>(Map.of(
                QuarkMindCaseFile.READY, true,
                QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools",
                QuarkMindCaseFile.STRATEGY_ROUTED_CONTEXT, "ZERG_ROACH_RUSH-ZERG-PvZ"));
        MutableMapCaseContext ctx = new MutableMapCaseContext(persisted);

        router.execute(ctx);

        verify(cbrStore, never()).retrieveSimilar(any(), any());
    }

    @Test
    void lowConfidence_skipsRouting() {
        setArchetype(StrategyArchetype.ZERG_ROACH_RUSH, 0.3);
        MutableMapCaseContext ctx = new MutableMapCaseContext(new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));

        router.execute(ctx);

        verify(cbrStore, never()).retrieveSimilar(any(), any());
        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID))
                .isEqualTo("strategy.drools");
    }

    @Test
    void pivotLimitEnforced() {
        setArchetype(StrategyArchetype.TERRAN_MARINE_RUSH, 0.9);
        Map<String, Object> persisted = new HashMap<>(Map.of(
                QuarkMindCaseFile.READY, true,
                QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools",
                QuarkMindCaseFile.STRATEGY_ROUTED_CONTEXT, "ZERG_ROACH_RUSH-ZERG-PvZ",
                QuarkMindCaseFile.STRATEGY_PIVOT_COUNT, 1));
        MutableMapCaseContext ctx = new MutableMapCaseContext(persisted);

        router.execute(ctx);

        verify(cbrStore, never()).retrieveSimilar(any(), any());
    }

    @Test
    void lastSelectedId_exposedForOutcomeRecorders() {
        setArchetype(StrategyArchetype.ZERG_ROACH_RUSH, 0.85);
        MutableMapCaseContext ctx = new MutableMapCaseContext(new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));
        router.execute(ctx);
        assertThat(router.lastSelectedId()).isNotNull();
    }

    @Test
    void emptyAssessments_selectsFallback() {
        when(broker.current(ScoutingIntelType.PATTERN_ASSESSMENT))
                .thenReturn(Optional.of(new PatternAssessmentPayload(List.of())));
        MutableMapCaseContext ctx = new MutableMapCaseContext(new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));

        router.execute(ctx);

        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID))
                .isEqualTo("strategy.drools");
        verify(cbrStore, never()).retrieveSimilar(any(), any());
    }

    @Test
    void firstRouting_writesInitialArchetype() {
        setArchetype(StrategyArchetype.ZERG_ROACH_RUSH, 0.85);
        MutableMapCaseContext ctx = new MutableMapCaseContext(new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));

        router.execute(ctx);

        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_INITIAL_ARCHETYPE))
                .isEqualTo("ZERG_ROACH_RUSH");
    }

    @Test
    void secondRouting_doesNotOverwriteInitialArchetype() {
        setArchetype(StrategyArchetype.ZERG_ROACH_RUSH, 0.85);
        MutableMapCaseContext ctx = new MutableMapCaseContext(new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));
        router.execute(ctx);

        setArchetype(StrategyArchetype.TERRAN_MARINE_RUSH, 0.9);
        router.execute(ctx);

        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_INITIAL_ARCHETYPE))
                .isEqualTo("ZERG_ROACH_RUSH");
    }

    private void setArchetype(StrategyArchetype archetype, double confidence) {
        var assessment = new PatternAssessment(archetype, confidence, 1000, "test");
        when(broker.current(ScoutingIntelType.PATTERN_ASSESSMENT))
                .thenReturn(Optional.of(new PatternAssessmentPayload(List.of(assessment))));
    }

    private StrategyTask stubStrategy(String id) {
        StrategyTask s = mock(StrategyTask.class);
        when(s.getId()).thenReturn(id);
        return s;
    }
}
