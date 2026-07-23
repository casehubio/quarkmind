package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.PatternAssessmentPayload;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.PatternAssessment;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SC2CbrRoutingIT {

    @Inject SC2CbrRetentionObserver retentionObserver;
    @Inject SC2StrategyRouterTask routerTask;
    @Inject ScoutingIntelBroker broker;
    @Inject GameSession gameSession;

    @Test
    void pastGames_influenceStrategySelection() {
        for (int i = 0; i < 3; i++) {
            retentionObserver.onOutcome(new CaseOutcomeEvent(
                    "starcraft-game", "default", UUID.randomUUID(),
                    Map.of(
                            QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.early-pressure",
                            QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                            QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.9),
                    "WIN", Instant.now(), Map.of()));
        }

        var assessment = new PatternAssessment(
                StrategyArchetype.ZERG_ROACH_RUSH, 0.9, 1000, "test");
        broker.update(new PatternAssessmentPayload(List.of(assessment)));

        MutableMapCaseContext ctx = new MutableMapCaseContext(
                new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));
        routerTask.execute(ctx);

        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID)).isNotNull();
        assertThat(ctx.getString(QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE))
                .isEqualTo("ZERG_ROACH_RUSH");
    }

    @Test
    void pivotLimit_preventsUnboundedRerouting() {
        var assessment1 = new PatternAssessment(
                StrategyArchetype.ZERG_ROACH_RUSH, 0.9, 1000, "test");
        broker.update(new PatternAssessmentPayload(List.of(assessment1)));

        MutableMapCaseContext ctx = new MutableMapCaseContext(
                new HashMap<>(Map.of(QuarkMindCaseFile.READY, true)));
        routerTask.execute(ctx);
        String firstSelection = ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID);
        assertThat(firstSelection).isNotNull();
        assertThat(ctx.getInt(QuarkMindCaseFile.STRATEGY_PIVOT_COUNT)).isEqualTo(0);

        var assessment2 = new PatternAssessment(
                StrategyArchetype.TERRAN_MARINE_RUSH, 0.9, 2000, "test");
        broker.update(new PatternAssessmentPayload(List.of(assessment2)));
        routerTask.execute(ctx);
        assertThat(ctx.getInt(QuarkMindCaseFile.STRATEGY_PIVOT_COUNT)).isEqualTo(1);

        var assessment3 = new PatternAssessment(
                StrategyArchetype.ZERG_MACRO, 0.9, 3000, "test");
        broker.update(new PatternAssessmentPayload(List.of(assessment3)));
        routerTask.execute(ctx);
        assertThat(ctx.getInt(QuarkMindCaseFile.STRATEGY_PIVOT_COUNT))
                .as("pivot limit (1) should prevent third routing").isEqualTo(1);
    }
}
