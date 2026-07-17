package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.quarkmind.agent.QuarkMindCaseFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

class SC2CbrRetentionObserverTest {

    CbrCaseMemoryStore store;
    SC2CbrRetentionObserver observer;

    @BeforeEach
    void setUp() {
        store = mock(CbrCaseMemoryStore.class);
        when(store.store(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("stored-case-1");
        observer = new SC2CbrRetentionObserver(store);
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

    private CaseOutcomeEvent buildEvent(String outcomeLabel, Map<String, Object> snapshot) {
        return new CaseOutcomeEvent(
                "starcraft-game", "tenant-1", UUID.randomUUID(),
                snapshot, outcomeLabel, Instant.now(), Map.of());
    }
}
