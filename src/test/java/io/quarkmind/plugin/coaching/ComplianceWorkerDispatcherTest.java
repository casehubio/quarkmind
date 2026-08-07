package io.quarkmind.plugin.coaching;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComplianceWorkerDispatcherTest {

    @Test
    void verdictMapping_complied_mapsToEndorsed() {
        assertEquals("ENDORSED", ComplianceWorkerDispatcher.mapVerdictToOutcome("COMPLIED"));
    }

    @Test
    void verdictMapping_partially_mapsToPartial() {
        assertEquals("PARTIAL", ComplianceWorkerDispatcher.mapVerdictToOutcome("PARTIALLY"));
    }

    @Test
    void verdictMapping_ignored_mapsToChallenged() {
        assertEquals("CHALLENGED", ComplianceWorkerDispatcher.mapVerdictToOutcome("IGNORED"));
    }

    @Test
    void verdictMapping_neutral_mapsToNeutral() {
        assertEquals("NEUTRAL", ComplianceWorkerDispatcher.mapVerdictToOutcome("NEUTRAL"));
    }

    @Test
    void verdictMapping_unknown_mapsToNeutral() {
        assertEquals("NEUTRAL", ComplianceWorkerDispatcher.mapVerdictToOutcome("SOMETHING_UNEXPECTED"));
    }

    @Test
    void cancelAll_discardsInFlightEntries() {
        var dispatcher = new ComplianceWorkerDispatcher();
        dispatcher.trackInFlight("corr-1");
        dispatcher.trackInFlight("corr-2");
        assertTrue(dispatcher.isInFlight("corr-1"));

        dispatcher.cancelAll();

        assertFalse(dispatcher.isInFlight("corr-1"));
        assertFalse(dispatcher.isInFlight("corr-2"));
    }

    @Test
    void removeFromFlight_returnsTrueIfPresent() {
        var dispatcher = new ComplianceWorkerDispatcher();
        dispatcher.trackInFlight("corr-1");
        assertTrue(dispatcher.removeFromFlight("corr-1"));
        assertFalse(dispatcher.removeFromFlight("corr-1"));
    }

    @Test
    void removeFromFlight_returnsFalseIfAbsent() {
        var dispatcher = new ComplianceWorkerDispatcher();
        assertFalse(dispatcher.removeFromFlight("corr-unknown"));
    }
}
