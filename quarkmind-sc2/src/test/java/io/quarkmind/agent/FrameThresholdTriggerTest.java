package io.quarkmind.agent;

import io.quarkmind.agency.milestone.MilestoneSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrameThresholdTriggerTest {

    private MilestoneSession session;

    @BeforeEach
    void setUp() {
        session = new MilestoneSession();
    }

    @Test
    void check_returnsEmpty_beforeFirstThreshold() {
        var trigger = new FrameThresholdTrigger(List.of(
            new FrameThresholdTrigger.Threshold(4032, 0.3)));

        assertThat(trigger.check(1000, session)).isEmpty();
    }

    @Test
    void check_firesAtThreshold() {
        var trigger = new FrameThresholdTrigger(List.of(
            new FrameThresholdTrigger.Threshold(4032, 0.3)));

        List<MilestoneEvent> events = trigger.check(4032, session);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).milestoneId()).isEqualTo("frame:4032");
        assertThat(events.get(0).temporalWeight()).isEqualTo(0.3);
    }

    @Test
    void check_firesPastThreshold() {
        var trigger = new FrameThresholdTrigger(List.of(
            new FrameThresholdTrigger.Threshold(4032, 0.3)));

        List<MilestoneEvent> events = trigger.check(5000, session);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).milestoneId()).isEqualTo("frame:4032");
    }

    @Test
    void check_doesNotDoubleFire() {
        var trigger = new FrameThresholdTrigger(List.of(
            new FrameThresholdTrigger.Threshold(4032, 0.3)));

        trigger.check(4032, session); // fires and marks
        List<MilestoneEvent> second = trigger.check(4033, session);

        assertThat(second).isEmpty();
    }

    @Test
    void check_firesMultipleThresholds_atDifferentFrames() {
        var trigger = new FrameThresholdTrigger(List.of(
            new FrameThresholdTrigger.Threshold(4032, 0.3),
            new FrameThresholdTrigger.Threshold(10752, 0.5)));

        List<MilestoneEvent> first = trigger.check(5000, session);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).temporalWeight()).isEqualTo(0.3);

        List<MilestoneEvent> second = trigger.check(11000, session);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).temporalWeight()).isEqualTo(0.5);
    }

    @Test
    void check_firesBothThresholds_whenFrameJumpsPastBoth() {
        var trigger = new FrameThresholdTrigger(List.of(
            new FrameThresholdTrigger.Threshold(4032, 0.3),
            new FrameThresholdTrigger.Threshold(10752, 0.5)));

        List<MilestoneEvent> events = trigger.check(15000, session);

        assertThat(events).hasSize(2);
    }

    @Test
    void check_returnsEmpty_whenNoThresholds() {
        var trigger = new FrameThresholdTrigger(List.of());
        assertThat(trigger.check(99999, session)).isEmpty();
    }
}
