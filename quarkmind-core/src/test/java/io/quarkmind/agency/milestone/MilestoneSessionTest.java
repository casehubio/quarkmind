package io.quarkmind.agency.milestone;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneSessionTest {

    @Test
    void hasFired_returnsFalse_beforeMarking() {
        var session = new MilestoneSession();
        assertFalse(session.hasFired("milestone-1"));
    }

    @Test
    void markFired_thenHasFired_returnsTrue() {
        var session = new MilestoneSession();
        session.markFired("milestone-1");
        assertTrue(session.hasFired("milestone-1"));
    }

    @Test
    void entryId_returnsEmpty_beforeSet() {
        var session = new MilestoneSession();
        assertTrue(session.entryId("strategy-1").isEmpty());
    }

    @Test
    void setEntryId_thenEntryId_returnsValue() {
        var session = new MilestoneSession();
        UUID id = UUID.randomUUID();
        session.setEntryId("strategy-1", id);
        assertEquals(id, session.entryId("strategy-1").orElseThrow());
    }

    @Test
    void reset_clearsAllState() {
        var session = new MilestoneSession();
        session.markFired("m1");
        session.setEntryId("s1", UUID.randomUUID());
        session.reset();
        assertFalse(session.hasFired("m1"));
        assertTrue(session.entryId("s1").isEmpty());
    }
}
