package io.quarkmind.agency.session;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgencySessionTest {

    @Test
    void newSession_hasRandomId() {
        var session = new AgencySession();
        assertNotNull(session.id());
    }

    @Test
    void reset_generatesNewId() {
        var session = new AgencySession();
        UUID first = session.id();
        session.reset();
        assertNotEquals(first, session.id());
    }

    @Test
    void setId_overridesCurrentId() {
        var session = new AgencySession();
        UUID custom = UUID.randomUUID();
        session.setId(custom);
        assertEquals(custom, session.id());
    }

    @Test
    void twoSessions_haveDifferentIds() {
        var s1 = new AgencySession();
        var s2 = new AgencySession();
        assertNotEquals(s1.id(), s2.id());
    }
}
