package io.quarkmind.agent;

import io.casehub.ledger.runtime.service.TrustGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyTrustRouterTest {

    @Mock TrustGateService trustGateService;

    StrategyTrustRouter router;

    static final List<String> ALL_CANDIDATES = List.of(
        "strategy.drools", "strategy.early-pressure", "strategy.economic-expansion");
    static final String CTX_UNKNOWN     = QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN;
    static final String CTX_AGGRESSIVE  = QuarkMindCapabilityTag.STRATEGY_VS_AGGRESSIVE;

    @BeforeEach
    void setUp() {
        // Use package-private constructor with explicit policy values (no CDI in unit tests)
        router = new StrategyTrustRouter(trustGateService,
            10,    // minimumObservations
            0.65,  // threshold
            0.08,  // borderlineMargin
            0.6);  // blendFactor
    }

    // --- Bootstrap phase (no trust data) ---

    @Test
    void allBootstrap_returnsFallback() {
        // No scores, no observations → all BOOTSTRAP → fallback wins tiebreaker
        for (String id : ALL_CANDIDATES) {
            when(trustGateService.decisionCount(id, CTX_UNKNOWN)).thenReturn(0);
            when(trustGateService.currentScore(id, CTX_UNKNOWN)).thenReturn(OptionalDouble.empty());
        }
        assertThat(router.select(ALL_CANDIDATES, CTX_UNKNOWN)).isEqualTo("strategy.drools");
    }

    @Test
    void allBootstrap_withSomeObservations_stillFallback() {
        // 5 observations < minimumObservations(10) → still BOOTSTRAP (phaseScore=0.5)
        // All tie at 0.5 → tiebreaker → "strategy.drools"
        for (String id : ALL_CANDIDATES) {
            when(trustGateService.decisionCount(id, CTX_UNKNOWN)).thenReturn(5);
            when(trustGateService.currentScore(id, CTX_UNKNOWN)).thenReturn(OptionalDouble.of(0.9));
        }
        assertThat(router.select(ALL_CANDIDATES, CTX_UNKNOWN)).isEqualTo("strategy.drools");
    }

    // --- QUALIFIED selection ---

    @Test
    void qualifiedCandidate_selectedOverBootstrap() {
        // early-pressure: 12 observations, 0.82 score → QUALIFIED
        when(trustGateService.decisionCount("strategy.early-pressure", CTX_AGGRESSIVE)).thenReturn(12);
        when(trustGateService.currentScore("strategy.early-pressure", CTX_AGGRESSIVE))
            .thenReturn(OptionalDouble.of(0.82));
        // others: bootstrap
        for (String id : List.of("strategy.drools", "strategy.economic-expansion")) {
            when(trustGateService.decisionCount(id, CTX_AGGRESSIVE)).thenReturn(0);
            when(trustGateService.currentScore(id, CTX_AGGRESSIVE)).thenReturn(OptionalDouble.empty());
        }
        assertThat(router.select(ALL_CANDIDATES, CTX_AGGRESSIVE)).isEqualTo("strategy.early-pressure");
    }

    @Test
    void higherQualifiedScore_wins() {
        // early-pressure: 0.82; economic-expansion: 0.90 → economic wins
        when(trustGateService.decisionCount("strategy.early-pressure", CTX_AGGRESSIVE)).thenReturn(12);
        when(trustGateService.currentScore("strategy.early-pressure", CTX_AGGRESSIVE))
            .thenReturn(OptionalDouble.of(0.82));
        when(trustGateService.decisionCount("strategy.economic-expansion", CTX_AGGRESSIVE)).thenReturn(15);
        when(trustGateService.currentScore("strategy.economic-expansion", CTX_AGGRESSIVE))
            .thenReturn(OptionalDouble.of(0.90));
        when(trustGateService.decisionCount("strategy.drools", CTX_AGGRESSIVE)).thenReturn(0);
        when(trustGateService.currentScore("strategy.drools", CTX_AGGRESSIVE))
            .thenReturn(OptionalDouble.empty());
        assertThat(router.select(ALL_CANDIDATES, CTX_AGGRESSIVE)).isEqualTo("strategy.economic-expansion");
    }

    // --- BORDERLINE exclusion ---

    @Test
    void borderlineCandidate_excluded_fallbackUsed() {
        // score 0.62 is within ±0.08 of threshold 0.65 → BORDERLINE (score 0.0)
        // Others also bootstrap → fallback
        for (String id : ALL_CANDIDATES) {
            when(trustGateService.decisionCount(id, CTX_AGGRESSIVE)).thenReturn(12);
            when(trustGateService.currentScore(id, CTX_AGGRESSIVE)).thenReturn(OptionalDouble.of(0.62));
        }
        // All borderline → fallback (drools is never excluded)
        assertThat(router.select(ALL_CANDIDATES, CTX_AGGRESSIVE)).isEqualTo("strategy.drools");
    }

    @Test
    void borderlineFallback_isStillSelectedWhenAllBorderline() {
        // drools itself is borderline — but fallback is always eligible
        for (String id : ALL_CANDIDATES) {
            when(trustGateService.decisionCount(id, CTX_AGGRESSIVE)).thenReturn(12);
            when(trustGateService.currentScore(id, CTX_AGGRESSIVE)).thenReturn(OptionalDouble.of(0.63));
        }
        // All borderline → designated fallback selected unconditionally
        assertThat(router.select(ALL_CANDIDATES, CTX_AGGRESSIVE)).isEqualTo("strategy.drools");
    }

    // --- EXCLUDED (below threshold, not borderline) ---

    @Test
    void excludedCandidate_otherBootstrapWins() {
        // early-pressure: 0.40 → EXCLUDED (below threshold−margin = 0.57)
        when(trustGateService.decisionCount("strategy.early-pressure", CTX_AGGRESSIVE)).thenReturn(12);
        when(trustGateService.currentScore("strategy.early-pressure", CTX_AGGRESSIVE))
            .thenReturn(OptionalDouble.of(0.40));
        // others: bootstrap → phase=BOOTSTRAP → phaseScore=0.5
        for (String id : List.of("strategy.drools", "strategy.economic-expansion")) {
            when(trustGateService.decisionCount(id, CTX_AGGRESSIVE)).thenReturn(0);
            when(trustGateService.currentScore(id, CTX_AGGRESSIVE)).thenReturn(OptionalDouble.empty());
        }
        // drools and economic are both BOOTSTRAP (phaseScore=0.5) → tie → drools wins
        assertThat(router.select(ALL_CANDIDATES, CTX_AGGRESSIVE)).isEqualTo("strategy.drools");
    }

    // --- Tiebreaker ---

    @Test
    void tieAtBootstrap_fallbackWins_notListOrder() {
        // Put early-pressure first in candidates — if list order were used it would win.
        // All BOOTSTRAP → phaseScore=0.5 for all. Tiebreaker: "strategy.drools" wins explicitly.
        List<String> reorderedCandidates = List.of(
            "strategy.early-pressure", "strategy.economic-expansion", "strategy.drools");
        for (String id : reorderedCandidates) {
            when(trustGateService.decisionCount(id, CTX_UNKNOWN)).thenReturn(0);
            when(trustGateService.currentScore(id, CTX_UNKNOWN)).thenReturn(OptionalDouble.empty());
        }
        assertThat(router.select(reorderedCandidates, CTX_UNKNOWN)).isEqualTo("strategy.drools");
    }

    // --- Edge cases ---

    @Test
    void emptyList_returnsFallback() {
        // No candidates → loop never runs → designated fallback returned
        assertThat(router.select(List.of(), CTX_UNKNOWN)).isEqualTo("strategy.drools");
    }

    @Test
    void singleCandidate_returnsIt() {
        when(trustGateService.decisionCount("strategy.drools", CTX_UNKNOWN)).thenReturn(0);
        when(trustGateService.currentScore("strategy.drools", CTX_UNKNOWN))
            .thenReturn(OptionalDouble.empty());
        assertThat(router.select(List.of("strategy.drools"), CTX_UNKNOWN)).isEqualTo("strategy.drools");
    }
}
