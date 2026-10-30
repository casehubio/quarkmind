package io.quarkmind.sc2.playbook;

public record CalibrationResult(
    boolean passed,
    int divergenceTick,
    int totalTicks,
    String divergenceField,
    String expected,
    String actual
) {
    public static CalibrationResult diverged(int tick, String field, String expected, String actual) {
        return new CalibrationResult(false, tick, tick, field, expected, actual);
    }

    public static CalibrationResult passed(int tickCount) {
        return new CalibrationResult(true, -1, tickCount, null, null, null);
    }

    public String report() {
        if (passed) {
            return String.format("CALIBRATION PASSED — %d ticks with no structural divergence", totalTicks);
        }
        return String.format(
            "DIVERGENCE at tick %d:%n  %s: expected %s (SC2), actual %s (Emulated)",
            divergenceTick, divergenceField, expected, actual);
    }
}
