package io.quarkmind.qa;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkmind.agent.cbr.SC2GameCbrCase;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CbrLearningCurveEndpointTest {

    CbrCaseMemoryStore store;
    CbrLearningCurveEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(CbrCaseMemoryStore.class);
        endpoint = new CbrLearningCurveEndpoint();
        var field = CbrLearningCurveEndpoint.class.getDeclaredField("cbrStore");
        field.setAccessible(true);
        field.set(endpoint, store);
    }

    @Test
    void learningCurve_emptyStore() {
        when(store.retrieveSimilar(any(), eq(SC2GameCbrCase.class))).thenReturn(List.of());
        Response r = endpoint.learningCurve();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertThat(body.get("totalGames")).isEqualTo(0);
        assertThat(body.get("overallWinRate")).isEqualTo(0.0);
        assertThat(body.get("trend")).isEqualTo("STABLE");
    }

    @Test
    void learningCurve_computesWinRate() {
        when(store.retrieveSimilar(any(), eq(SC2GameCbrCase.class)))
                .thenReturn(List.of(
                        scored("WIN", "PvZ", "strategy.a", 1),
                        scored("WIN", "PvZ", "strategy.a", 2),
                        scored("LOSS", "PvT", "strategy.b", 3),
                        scored("WIN", "PvT", "strategy.a", 4),
                        scored("LOSS", "PvZ", "strategy.b", 5)));

        Response r = endpoint.learningCurve();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertThat(body.get("totalGames")).isEqualTo(5);
        assertThat((double) body.get("overallWinRate")).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void strategyEvolution_groupsByStrategy() {
        when(store.retrieveSimilar(any(), eq(SC2GameCbrCase.class)))
                .thenReturn(List.of(
                        scored("WIN", "PvZ", "strategy.a", 1),
                        scored("LOSS", "PvZ", "strategy.a", 2),
                        scored("WIN", "PvT", "strategy.b", 3)));

        Response r = endpoint.strategyEvolution();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strategies = (List<Map<String, Object>>) body.get("strategies");
        assertThat(strategies).hasSize(2);
    }

    @Test
    void caseStats_reportsTier2Coverage() {
        var tier1Case = new SC2GameCbrCase("p", "s", "WIN", null, Map.of(
                "enemy_archetype", FeatureValue.string("X")));
        var tier2Case = new SC2GameCbrCase("p", "s", "WIN", null, Map.of(
                "enemy_archetype", FeatureValue.string("X"),
                "moment_count", FeatureValue.number(5)));

        when(store.retrieveSimilar(any(), eq(SC2GameCbrCase.class)))
                .thenReturn(List.of(
                        new ScoredCbrCase<>(tier1Case, 0.5),
                        new ScoredCbrCase<>(tier2Case, 0.5)));

        Response r = endpoint.caseStats();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertThat(body.get("totalCases")).isEqualTo(2);
        assertThat((double) body.get("tier2Coverage")).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
    }

    private static ScoredCbrCase<SC2GameCbrCase> scored(String outcome, String matchup, String strategy, int order) {
        var c = new SC2GameCbrCase("problem", strategy, outcome, null, Map.of(
                "matchup", FeatureValue.string(matchup),
                "enemy_archetype", FeatureValue.string("ARCH_" + order)));
        return new ScoredCbrCase<>(c, "case-" + order, 0.5, false, Map.of(),
                Instant.EPOCH.plusSeconds(order * 3600L), null, null);
    }
}
