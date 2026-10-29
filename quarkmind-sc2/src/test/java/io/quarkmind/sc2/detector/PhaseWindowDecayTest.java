package io.quarkmind.sc2.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhaseWindowDecayTest {

    @Test
    void insideWindowReturnsOne() {
        double w = PhaseWindowDecay.weight(4.0, 2.0, 6.0, 1.0);
        assertEquals(1.0, w, 1e-6);
    }

    @Test
    void atWindowStartReturnsOne() {
        double w = PhaseWindowDecay.weight(2.0, 2.0, 6.0, 1.0);
        assertEquals(1.0, w, 1e-6);
    }

    @Test
    void atWindowEndReturnsOne() {
        double w = PhaseWindowDecay.weight(6.0, 2.0, 6.0, 1.0);
        assertEquals(1.0, w, 1e-6);
    }

    @Test
    void beforeWindowReturnsFloor() {
        double w = PhaseWindowDecay.weight(0.5, 2.0, 6.0, 1.0);
        assertEquals(0.01, w, 1e-6);
    }

    @Test
    void afterWindowReturnsFloor() {
        double w = PhaseWindowDecay.weight(8.0, 2.0, 6.0, 1.0);
        assertEquals(0.01, w, 1e-6);
    }

    @Test
    void rampBeforeWindowIsBetweenFloorAndOne() {
        double w = PhaseWindowDecay.weight(1.5, 2.0, 6.0, 1.0);
        assertTrue(w > 0.01 && w < 1.0, "ramp weight should be between floor and 1.0, got " + w);
    }

    @Test
    void rampAfterWindowIsBetweenFloorAndOne() {
        double w = PhaseWindowDecay.weight(6.5, 2.0, 6.0, 1.0);
        assertTrue(w > 0.01 && w < 1.0, "ramp weight should be between floor and 1.0, got " + w);
    }

    @Test
    void floorIsNeverZero() {
        double w = PhaseWindowDecay.weight(100.0, 0.0, 4.0, 1.0);
        assertTrue(w >= 0.01, "weight should never be less than floor");
    }

    @Test
    void rampMidpointIsApproximatelyHalf() {
        double w = PhaseWindowDecay.weight(1.5, 2.0, 6.0, 1.0);
        assertTrue(w > 0.4 && w < 0.6, "ramp midpoint should be ~0.5, got " + w);
    }

    @Test
    void zeroRampGivesStepFunction() {
        assertEquals(1.0, PhaseWindowDecay.weight(3.0, 2.0, 6.0, 0.0), 1e-6);
        assertEquals(0.01, PhaseWindowDecay.weight(1.99, 2.0, 6.0, 0.0), 1e-6);
        assertEquals(0.01, PhaseWindowDecay.weight(6.01, 2.0, 6.0, 0.0), 1e-6);
    }
}
