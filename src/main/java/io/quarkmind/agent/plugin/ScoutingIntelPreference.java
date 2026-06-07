package io.quarkmind.agent.plugin;

import io.casehub.platform.api.preferences.SingleValuePreference;

public record ScoutingIntelPreference(Object value) implements SingleValuePreference {

    public boolean asBoolean() {
        if (!(value instanceof Boolean b)) throw new IllegalStateException(
            "Expected Boolean, got " + value.getClass().getSimpleName());
        return b;
    }

    public double asDouble() {
        if (!(value instanceof Number n)) throw new IllegalStateException(
            "Expected Number, got " + value.getClass().getSimpleName());
        return n.doubleValue();
    }

    public int asInt() {
        if (!(value instanceof Number n)) throw new IllegalStateException(
            "Expected Number, got " + value.getClass().getSimpleName());
        return n.intValue();
    }

    public static ScoutingIntelPreference ofBoolean(boolean v) { return new ScoutingIntelPreference(v); }
    public static ScoutingIntelPreference ofDouble(double v)   { return new ScoutingIntelPreference(v); }
    public static ScoutingIntelPreference ofInt(int v)         { return new ScoutingIntelPreference(v); }

    public static ScoutingIntelPreference parseBoolean(String s) { return ofBoolean(Boolean.parseBoolean(s)); }
    public static ScoutingIntelPreference parseDouble(String s)  { return ofDouble(Double.parseDouble(s)); }
    public static ScoutingIntelPreference parseInt(String s)     { return ofInt(Integer.parseInt(s)); }
}
