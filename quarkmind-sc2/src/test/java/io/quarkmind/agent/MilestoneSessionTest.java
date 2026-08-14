package io.quarkmind.agent;

import io.quarkmind.agency.milestone.MilestoneSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MilestoneSessionTest {

    private MilestoneSession session;

    @BeforeEach
    void setUp() {
        session = new MilestoneSession();
    }

    @Test
    void entryId_returnsEmpty_whenNotSet() {
        assertThat(session.entryId("strategy.drools")).isEmpty();
    }

    @Test
    void entryId_returnsValue_afterSet() {
        UUID id = UUID.randomUUID();
        session.setEntryId("strategy.drools", id);
        assertThat(session.entryId("strategy.drools")).contains(id);
    }

    @Test
    void entryId_separatePerStrategy() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        session.setEntryId("strategy.drools", id1);
        session.setEntryId("strategy.early-pressure", id2);
        assertThat(session.entryId("strategy.drools")).contains(id1);
        assertThat(session.entryId("strategy.early-pressure")).contains(id2);
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
        session.setEntryId("strategy.drools", id);
        session.markFired("frame:4032");

        session.reset();

        assertThat(session.entryId("strategy.drools")).isEmpty();
        assertThat(session.hasFired("frame:4032")).isFalse();
    }
}
