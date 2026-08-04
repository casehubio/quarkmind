package io.quarkmind.qa.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.coaching.CoachingDomain;
import io.quarkmind.plugin.coaching.CoachingUrgencyTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pattern_event_serializes_to_json() throws Exception {
        var assessment = new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.87, 1000, "6+ lings");
        var event = new WorkbenchEvent("pattern", new PatternPayload(List.of(new EnrichedAssessment(assessment, null))));

        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"type\":\"pattern\""));
        assertTrue(json.contains("ZERG_ZERGLING_RUSH"));
        assertTrue(json.contains("\"confidence\":0.87"));
    }

    @Test
    void coaching_event_serializes_to_json() throws Exception {
        var event = new WorkbenchEvent("coaching",
            new CoachingPayload("build stalkers", CoachingDomain.MILITARY, CoachingUrgencyTier.CRISIS, 500L, "corr-test"));

        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"type\":\"coaching\""));
        assertTrue(json.contains("\"advice\":\"build stalkers\""));
        assertTrue(json.contains("\"domain\":\"MILITARY\""));
    }

    @Test
    void strategy_event_serializes_to_json() throws Exception {
        var event = new WorkbenchEvent("strategy",
            new StrategyPayload("reactive-blink", StrategyArchetype.ZERG_ZERGLING_RUSH, 0.82, 1));

        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"type\":\"strategy\""));
        assertTrue(json.contains("\"strategyId\":\"reactive-blink\""));
    }

    @Test
    void compliance_event_serializes_to_json() throws Exception {
        var event = new WorkbenchEvent("coaching_compliance",
            new CoachingCompliancePayload(500L, CoachingDomain.BUILD, "ENDORSED", "corr-test"));

        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"coaching_compliance\""));
        assertTrue(json.contains("\"status\":\"ENDORSED\""));
    }
}
