package io.quarkmind.qa.workbench;

import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.agent.cbr.StrategySelectionPublished;
import io.quarkmind.agent.plugin.PatternAssessmentPublished;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.coaching.CoachingAdvice;
import io.quarkmind.plugin.coaching.CoachingAdvicePublished;
import io.quarkmind.plugin.coaching.CoachingComplianceResolved;
import io.quarkmind.plugin.coaching.CoachingDomain;
import io.quarkmind.plugin.coaching.CoachingUrgencyTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchEnricherTest {

    private StrategyTaxonomy taxonomy;
    private CapturingBroadcaster broadcaster;
    private WorkbenchEnricher enricher;

    @BeforeEach
    void setup() {
        taxonomy = new StrategyTaxonomy();
        broadcaster = new CapturingBroadcaster();
        enricher = new WorkbenchEnricher(taxonomy, broadcaster);
    }

    @Test
    void enriches_pattern_assessments_with_counter_info() {
        var assessment = new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.87, 1000, "6+ lings");
        enricher.onPatternAssessment(new PatternAssessmentPublished(List.of(assessment)));

        assertEquals(1, broadcaster.events.size());
        var event = broadcaster.events.getFirst();
        assertEquals("pattern", event.type());
        var payload = (PatternPayload) event.payload();
        assertEquals(1, payload.assessments().size());
        assertEquals(StrategyArchetype.ZERG_ZERGLING_RUSH, payload.assessments().getFirst().assessment().archetype());
    }

    @Test
    void enrichment_failure_for_one_archetype_still_produces_event() {
        var a1 = new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.87, 1000, "rush");
        var a2 = new PatternAssessment(StrategyArchetype.ZERG_MACRO, 0.31, 1000, "macro");
        enricher.onPatternAssessment(new PatternAssessmentPublished(List.of(a1, a2)));

        var event = broadcaster.events.getFirst();
        var payload = (PatternPayload) event.payload();
        assertEquals(2, payload.assessments().size());
    }

    @Test
    void coaching_advice_produces_coaching_event() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY, null, 200);
        enricher.onCoachingAdvice(new CoachingAdvicePublished(advice, CoachingUrgencyTier.CRISIS, 500L, "corr-test"));

        var event = broadcaster.events.getFirst();
        assertEquals("coaching", event.type());
        var payload = (CoachingPayload) event.payload();
        assertEquals("build stalkers", payload.advice());
        assertEquals(CoachingDomain.MILITARY, payload.domain());
    }

    @Test
    void compliance_resolved_produces_compliance_event() {
        enricher.onCoachingCompliance(new CoachingComplianceResolved(500L, CoachingDomain.BUILD, "ENDORSED", "corr-test"));

        var event = broadcaster.events.getFirst();
        assertEquals("coaching_compliance", event.type());
        var payload = (CoachingCompliancePayload) event.payload();
        assertEquals("ENDORSED", payload.status());
    }

    @Test
    void strategy_selection_produces_strategy_event() {
        enricher.onStrategySelection(new StrategySelectionPublished("reactive-blink", StrategyArchetype.ZERG_ZERGLING_RUSH, 0.82, 1));

        var event = broadcaster.events.getFirst();
        assertEquals("strategy", event.type());
        var payload = (StrategyPayload) event.payload();
        assertEquals("reactive-blink", payload.strategyId());
        assertEquals(0.82, payload.confidence());
    }

    private static class CapturingBroadcaster extends WorkbenchBroadcaster {
        final List<WorkbenchEvent> events = new ArrayList<>();

        @Override
        public void broadcast(WorkbenchEvent event) {
            events.add(event);
        }
    }
}
