package io.quarkmind.plugin.coaching;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComplianceVerdictTest {

    @Test
    void parse_validJson_allFieldsExtracted() {
        var verdict = ComplianceVerdict.parse("""
            {"verdict": "COMPLIED", "confidence": 0.85, "reasoning": "Built stalkers as advised"}
            """);
        assertEquals("COMPLIED", verdict.verdict());
        assertEquals(0.85, verdict.confidence(), 0.001);
        assertEquals("Built stalkers as advised", verdict.reasoning());
    }

    @Test
    void parse_markdownFencedJson_stripped() {
        var verdict = ComplianceVerdict.parse("""
            ```json
            {"verdict": "IGNORED", "confidence": 0.9, "reasoning": "No new units"}
            ```
            """);
        assertEquals("IGNORED", verdict.verdict());
        assertEquals(0.9, verdict.confidence(), 0.001);
    }

    @Test
    void parse_malformedJson_returnsNeutral() {
        var verdict = ComplianceVerdict.parse("not json at all");
        assertEquals("NEUTRAL", verdict.verdict());
        assertEquals(0.0, verdict.confidence(), 0.001);
    }

    @Test
    void parse_missingFields_usesDefaults() {
        var verdict = ComplianceVerdict.parse("""
            {"verdict": "PARTIALLY"}
            """);
        assertEquals("PARTIALLY", verdict.verdict());
        assertEquals(0.5, verdict.confidence(), 0.001);
        assertEquals("", verdict.reasoning());
    }

    @Test
    void parse_nullInput_returnsNeutral() {
        var verdict = ComplianceVerdict.parse(null);
        assertEquals("NEUTRAL", verdict.verdict());
    }

    @Test
    void parse_emptyInput_returnsNeutral() {
        var verdict = ComplianceVerdict.parse("");
        assertEquals("NEUTRAL", verdict.verdict());
    }
}
