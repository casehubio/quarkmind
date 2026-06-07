package io.quarkmind.agent.plugin;

import io.casehub.platform.api.preferences.SingleValuePreference;

public record ScoutingIntelPreference(Object value) implements SingleValuePreference {

    public boolean asBoolean() { return (Boolean) value; }
    public double  asDouble()  { return ((Number) value).doubleValue(); }
    public int     asInt()     { return ((Number) value).intValue(); }

    public static ScoutingIntelPreference ofBoolean(boolean v) { return new ScoutingIntelPreference(v); }
    public static ScoutingIntelPreference ofDouble(double v)   { return new ScoutingIntelPreference(v); }
    public static ScoutingIntelPreference ofInt(int v)         { return new ScoutingIntelPreference(v); }

    public static ScoutingIntelPreference parseBoolean(String s) { return ofBoolean(Boolean.parseBoolean(s)); }
    public static ScoutingIntelPreference parseDouble(String s)  { return ofDouble(Double.parseDouble(s)); }
    public static ScoutingIntelPreference parseInt(String s)     { return ofInt(Integer.parseInt(s)); }
}
