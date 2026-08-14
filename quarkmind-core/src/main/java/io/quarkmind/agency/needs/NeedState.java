package io.quarkmind.agency.needs;

import java.util.HashMap;
import java.util.Map;

public class NeedState {

    private final Map<String, Double> levels = new HashMap<>();
    private static final double MAX = 100.0;
    private static final double MIN = 0.0;

    public void set(String need, double value) {
        levels.put(need, clamp(value));
    }

    public double get(String need) {
        return levels.getOrDefault(need, 0.0);
    }

    public void decay(String need, double rate) {
        set(need, get(need) - rate);
    }

    public void satisfy(String need, double amount) {
        set(need, get(need) + amount);
    }

    private double clamp(double value) {
        return Math.max(MIN, Math.min(MAX, value));
    }
}
