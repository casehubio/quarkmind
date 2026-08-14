package io.quarkmind.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryMilestoneSessionTest {

    private AdvisoryMilestoneSession session;

    @BeforeEach
    void setUp() {
        session = new AdvisoryMilestoneSession();
    }

    @Test
    void entryId_returnsEmpty_whenNotSet() {
        assertThat(session.entryId("claude:crisis@v1")).isEmpty();
    }

    @Test
    void entryId_returnsValue_afterSet() {
        UUID id = UUID.randomUUID();
        session.setEntryId("claude:crisis@v1", id);
        assertThat(session.entryId("claude:crisis@v1")).contains(id);
    }

    @Test
    void entryId_separatePerAdvisor() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        session.setEntryId("claude:crisis@v1", id1);
        session.setEntryId("claude:economic@v1", id2);
        assertThat(session.entryId("claude:crisis@v1")).contains(id1);
        assertThat(session.entryId("claude:economic@v1")).contains(id2);
    }

    @Test
    void hasFired_returnsFalse_initially() {
        assertThat(session.hasFired("frame:4032")).isFalse();
    }

    @Test
    void hasFired_returnsTrue_afterMark() {
        session.markFired("frame:4032");
        assertThat(session.hasFired("frame:4032")).isTrue();
    }

    @Test
    void reset_clearsAllState() {
        UUID id = UUID.randomUUID();
        session.setEntryId("claude:crisis@v1", id);
        session.markFired("frame:4032");

        session.reset();

        assertThat(session.entryId("claude:crisis@v1")).isEmpty();
        assertThat(session.hasFired("frame:4032")).isFalse();
    }

    @Test
    void implementsMilestoneTracker() {
        assertThat(session).isInstanceOf(MilestoneTracker.class);
    }
}
