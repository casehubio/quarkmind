package io.quarkmind.sc2.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemporalAssessmentTrackerTest {

    @Test
    void singlePosteriorReturnedAsIs() {
        var tracker = new TemporalAssessmentTracker(10);
        tracker.recordPosterior(new double[]{0.8, 0.15, 0.05});

        double[] smoothed = tracker.getSmoothedPosterior();
        assertEquals(0.8, smoothed[0], 0.001);
        assertEquals(0.15, smoothed[1], 0.001);
        assertEquals(0.05, smoothed[2], 0.001);
    }

    @Test
    void windowAveragesSmoothsJitter() {
        var tracker = new TemporalAssessmentTracker(3);
        tracker.recordPosterior(new double[]{0.9, 0.1});
        tracker.recordPosterior(new double[]{0.7, 0.3});
        tracker.recordPosterior(new double[]{0.8, 0.2});

        double[] smoothed = tracker.getSmoothedPosterior();
        assertEquals(0.8, smoothed[0], 0.01);
        assertEquals(0.2, smoothed[1], 0.01);
    }

    @Test
    void windowDropsOldEvidence() {
        var tracker = new TemporalAssessmentTracker(2);
        tracker.recordPosterior(new double[]{0.9, 0.1});
        tracker.recordPosterior(new double[]{0.3, 0.7});
        tracker.recordPosterior(new double[]{0.4, 0.6});

        double[] smoothed = tracker.getSmoothedPosterior();
        assertEquals(0.35, smoothed[0], 0.01);
        assertEquals(0.65, smoothed[1], 0.01);
    }

    @Test
    void detectsTransitionWhenDominantShifts() {
        var tracker = new TemporalAssessmentTracker(3);
        tracker.recordPosterior(new double[]{0.8, 0.2});
        tracker.recordPosterior(new double[]{0.7, 0.3});
        assertFalse(tracker.detectTransition());

        tracker.recordPosterior(new double[]{0.3, 0.7});
        tracker.recordPosterior(new double[]{0.2, 0.8});
        assertTrue(tracker.detectTransition());
    }

    @Test
    void noTransitionWhenDominantStable() {
        var tracker = new TemporalAssessmentTracker(3);
        tracker.recordPosterior(new double[]{0.6, 0.4});
        assertFalse(tracker.detectTransition());

        tracker.recordPosterior(new double[]{0.7, 0.3});
        assertFalse(tracker.detectTransition());

        tracker.recordPosterior(new double[]{0.55, 0.45});
        assertFalse(tracker.detectTransition());
    }

    @Test
    void resetClearsWindow() {
        var tracker = new TemporalAssessmentTracker(5);
        tracker.recordPosterior(new double[]{0.8, 0.2});
        tracker.reset();
        assertNull(tracker.getSmoothedPosterior());
    }

    @Test
    void resetClearsTransitionState() {
        var tracker = new TemporalAssessmentTracker(5);
        tracker.recordPosterior(new double[]{0.8, 0.2});
        tracker.detectTransition();
        tracker.reset();

        tracker.recordPosterior(new double[]{0.2, 0.8});
        assertFalse(tracker.detectTransition(),
            "First observation after reset should not trigger transition");
    }

    @Test
    void threeWayPosteriorTracksCorrectly() {
        var tracker = new TemporalAssessmentTracker(3);
        tracker.recordPosterior(new double[]{0.6, 0.3, 0.1});
        tracker.recordPosterior(new double[]{0.5, 0.35, 0.15});
        tracker.recordPosterior(new double[]{0.4, 0.4, 0.2});

        double[] smoothed = tracker.getSmoothedPosterior();
        assertEquals(0.5, smoothed[0], 0.01);
        assertEquals(0.35, smoothed[1], 0.01);
        assertEquals(0.15, smoothed[2], 0.01);
    }
}
