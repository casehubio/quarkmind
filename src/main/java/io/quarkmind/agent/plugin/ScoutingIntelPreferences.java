package io.quarkmind.agent.plugin;

import io.casehub.platform.api.preferences.PreferenceKey;

public final class ScoutingIntelPreferences {

    public static final PreferenceKey<ScoutingIntelPreference> THREAT_POSITION_MIN_DISTANCE =
        new PreferenceKey<>("scouting.intel.dispatch", "threat-position.min-distance",
            ScoutingIntelPreference.ofDouble(0.0), ScoutingIntelPreference::parseDouble);

    public static final PreferenceKey<ScoutingIntelPreference> ARMY_SIZE_MIN_DELTA =
        new PreferenceKey<>("scouting.intel.dispatch", "army-size.min-delta",
            ScoutingIntelPreference.ofInt(1), ScoutingIntelPreference::parseInt);

    public static final PreferenceKey<ScoutingIntelPreference> POSTURE_DISPATCH_ENABLED =
        new PreferenceKey<>("scouting.intel.dispatch", "posture.enabled",
            ScoutingIntelPreference.ofBoolean(true), ScoutingIntelPreference::parseBoolean);

    public static final PreferenceKey<ScoutingIntelPreference> TIMING_ALERT_DISPATCH_ENABLED =
        new PreferenceKey<>("scouting.intel.dispatch", "timing-alert.enabled",
            ScoutingIntelPreference.ofBoolean(true), ScoutingIntelPreference::parseBoolean);

    public static final PreferenceKey<ScoutingIntelPreference> BUILD_ORDER_DISPATCH_ENABLED =
        new PreferenceKey<>("scouting.intel.dispatch", "build-order.enabled",
            ScoutingIntelPreference.ofBoolean(true), ScoutingIntelPreference::parseBoolean);

    public static PreferenceKey<ScoutingIntelPreference> consumerKey(String pluginId, ScoutingIntelType type) {
        return new PreferenceKey<>(
            "scouting.intel.consumer." + pluginId,
            toKebab(type),
            ScoutingIntelPreference.ofBoolean(defaultEnabled(type)),
            ScoutingIntelPreference::parseBoolean);
    }

    static String toKebab(ScoutingIntelType type) {
        return type.name().toLowerCase().replace('_', '-');
    }

    static boolean defaultEnabled(ScoutingIntelType type) {
        return switch (type) {
            case THREAT_POSITION, POSTURE, TIMING_ALERT -> true;
            case ARMY_SIZE, BUILD_ORDER -> false;
        };
    }

    private ScoutingIntelPreferences() {}
}
