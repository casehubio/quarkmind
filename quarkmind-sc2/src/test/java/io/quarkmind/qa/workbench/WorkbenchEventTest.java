package io.quarkmind.qa.workbench;

import io.quarkmind.agent.cbr.StrategySelectionPublished;
import io.quarkmind.agent.plugin.PatternAssessmentPublished;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.coaching.CoachingAdvice;
import io.quarkmind.plugin.coaching.CoachingAdvicePublished;
import io.quarkmind.plugin.coaching.CoachingComplianceResolved;
import io.quarkmind.plugin.coaching.CoachingDomain;
import io.quarkmind.plugin.coaching.CoachingUrgencyTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchEventTest {

    @Test
    void patternAssessmentPublished_carriesAssessments() {
        var a = new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.87, 1000, "6+ lings", AssessmentSource.DROOLS);
        var event = new PatternAssessmentPublished(List.of(a));
        assertEquals(1, event.assessments().size());
        assertEquals(StrategyArchetype.ZERG_ZERGLING_RUSH, event.assessments().getFirst().archetype());
    }

    @Test
    void coachingAdvicePublished_carriesFields() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY, null, 200);
        var event = new CoachingAdvicePublished(advice, CoachingUrgencyTier.CRISIS, 500L, "corr-test");
        assertEquals("build stalkers", event.advice().advice());
        assertEquals(CoachingUrgencyTier.CRISIS, event.urgencyTier());
        assertEquals(500L, event.gameFrame());
    }

    @Test
    void coachingComplianceResolved_carriesFields() {
        var event = new CoachingComplianceResolved(500L, CoachingDomain.MILITARY, "complied", "corr-test");
        assertEquals(CoachingDomain.MILITARY, event.domain());
        assertEquals("complied", event.status());
    }

    @Test
    void strategySelectionPublished_carriesFields() {
        var event = new StrategySelectionPublished("reactive-blink", StrategyArchetype.ZERG_ZERGLING_RUSH, 0.82, 1, 5000L);
        assertEquals("reactive-blink", event.strategyId());
        assertEquals(0.82, event.confidence());
        assertEquals(1, event.pivotCount());
    }

    @Test
    void commentary_payload_serializes_correctly() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.findAndRegisterModules();
        var payload = new CommentaryPayload("Great expansion!", "commentary.reactive",
                                            "REACTIVE", 1500, "commentator-atlas", 120L, java.time.Instant.parse("2026-08-26T12:00:00Z"));
        var    event = new WorkbenchEvent("commentary", payload);
        String json  = objectMapper.writeValueAsString(event);
        assertTrue(json.contains("\"type\":\"commentary\""));
        assertTrue(json.contains("\"text\":\"Great expansion!\""));
        assertTrue(json.contains("\"gameFrame\":1500"));
        assertTrue(json.contains("\"workerId\":\"commentator-atlas\""));
        assertTrue(json.contains("\"commentaryType\":\"REACTIVE\""));
        assertTrue(json.contains("\"capability\":\"commentary.reactive\""));
        assertTrue(json.contains("\"latencyMs\":120"));
    }
}
