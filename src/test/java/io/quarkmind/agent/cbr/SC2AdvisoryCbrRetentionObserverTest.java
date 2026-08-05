package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.*;
import io.casehub.platform.api.path.Path;
import io.quarkmind.agent.AdvisoryInvocationCounter;
import io.quarkmind.agent.QuarkMindCaseFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SC2AdvisoryCbrRetentionObserverTest {

    private RecordingCbrStore cbrStore;
    private AdvisoryInvocationCounter invocationCounter;
    private SC2AdvisoryCbrRetentionObserver observer;

    @BeforeEach
    void setUp() {
        cbrStore = new RecordingCbrStore();
        invocationCounter = new AdvisoryInvocationCounter();
        observer = new SC2AdvisoryCbrRetentionObserver(cbrStore, invocationCounter);
    }

    @Test
    void storesOneCase_perInvokedAdvisor() {
        invocationCounter.record("claude:crisis-aggressive@v1");
        invocationCounter.record("claude:strategic-bold@v1");

        observer.onOutcome(outcomeEvent("WIN"));

        assertThat(cbrStore.storedCases).hasSize(2);
        assertThat(cbrStore.storedCases).extracting(r -> r.cbrCase.solution())
                .containsExactlyInAnyOrder("claude:crisis-aggressive@v1", "claude:strategic-bold@v1");
    }

    @Test
    void capturesCorrectFeatures() {
        invocationCounter.record("claude:crisis-aggressive@v1");

        observer.onOutcome(outcomeEvent("WIN"));

        assertThat(cbrStore.storedCases).hasSize(1);
        SC2AdvisoryCbrCase stored = cbrStore.storedCases.get(0).cbrCase;
        assertThat(((FeatureValue.StringVal) stored.features().get("enemy_archetype")).value()).isEqualTo("ZERG_ROACH_RUSH");
        assertThat(((FeatureValue.StringVal) stored.features().get("enemy_race")).value()).isEqualTo("ZERG");
        assertThat(((FeatureValue.StringVal) stored.features().get("matchup")).value()).isEqualTo("PvZ");
        assertThat(((FeatureValue.StringVal) stored.features().get("strategy_id")).value()).isEqualTo("strategy.early-pressure");
        assertThat(((FeatureValue.StringVal) stored.features().get("game_phase")).value()).isEqualTo("mid");
    }

    @Test
    void recordsOutcome_win() {
        invocationCounter.record("claude:crisis-aggressive@v1");

        observer.onOutcome(outcomeEvent("WIN"));

        assertThat(cbrStore.outcomes).hasSize(1);
        assertThat(cbrStore.outcomes.get(0).outcome.detail()).isEqualTo("WIN");
        assertThat(cbrStore.outcomes.get(0).outcome.successRate()).isEqualTo(1.0);
    }

    @Test
    void recordsOutcome_loss() {
        invocationCounter.record("claude:crisis-aggressive@v1");

        observer.onOutcome(outcomeEvent("LOSS"));

        assertThat(cbrStore.outcomes.get(0).outcome.successRate()).isEqualTo(0.0);
    }

    @Test
    void skipsWhenNoAdvisorsInvoked() {
        observer.onOutcome(outcomeEvent("WIN"));

        assertThat(cbrStore.storedCases).isEmpty();
    }

    @Test
    void skipsWhenUnknownOutcome() {
        invocationCounter.record("claude:crisis-aggressive@v1");

        observer.onOutcome(outcomeEvent("UNKNOWN"));

        assertThat(cbrStore.storedCases).isEmpty();
    }

    @Test
    void skipsWhenNoArchetypeInSnapshot() {
        invocationCounter.record("claude:crisis-aggressive@v1");

        CaseOutcomeEvent event = new CaseOutcomeEvent(
                "starcraft-game", "test-tenant", UUID.randomUUID(),
                Map.of(QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.drools"),
                "WIN", Instant.now(), Map.of());

        observer.onOutcome(event);

        assertThat(cbrStore.storedCases).isEmpty();
    }

    @Test
    void usesCorrectCbrType() {
        invocationCounter.record("claude:crisis-aggressive@v1");

        observer.onOutcome(outcomeEvent("WIN"));

        assertThat(cbrStore.storedCases.get(0).cbrType).isEqualTo("sc2-advisory");
    }

    private CaseOutcomeEvent outcomeEvent(String outcome) {
        return new CaseOutcomeEvent(
                "starcraft-game", "test-tenant", UUID.randomUUID(),
                Map.of(
                        QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.early-pressure",
                        QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, "ZERG_ROACH_RUSH",
                        QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, 0.85,
                        QuarkMindCaseFile.GAME_PHASE, "mid"),
                outcome, Instant.now(), Map.of());
    }

    record StoredCase(SC2AdvisoryCbrCase cbrCase, String cbrType) {}
    record StoredOutcome(String caseId, CbrOutcome outcome) {}

    static class RecordingCbrStore implements CbrCaseMemoryStore {
        final List<StoredCase> storedCases = new ArrayList<>();
        final List<StoredOutcome> outcomes = new ArrayList<>();
        private int counter = 0;

        @Override
        public String store(CbrCase cbrCase, String tenancyId, String correlationId,
                            MemoryDomain domain, String sourceId, String cbrType, Path path) {
            storedCases.add(new StoredCase((SC2AdvisoryCbrCase) cbrCase, cbrType));
            return "case-" + counter++;
        }

        @Override public void recordOutcome(String caseId, String cbrType, CbrOutcome outcome) {
            outcomes.add(new StoredOutcome(caseId, outcome));
        }

        @Override public void registerSchema(CbrFeatureSchema schema) {}
        @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> type) { return List.of(); }
        @Override public Integer erase(EraseRequest request) { return 0; }
        @Override public Integer eraseEntity(String entityId, String tenancyId) { return 0; }
        @Override public Integer eraseByScope(Path scope, String tenancyId) { return 0; }
        @Override public Integer purge(CbrRetentionPolicy policy) { return 0; }
        @Override public void supersede(String caseId, String cbrType, String reason, String supersededBy) {}
        @Override public void reinstate(String caseId, String cbrType) {}
        @Override public SupersessionStatus getSupersessionStatus(String caseId, String cbrType) { return null; }
        @Override public List<SupersessionStatus> findSupersededCases(String cbrType, MemoryDomain domain) { return List.of(); }
    }
}
