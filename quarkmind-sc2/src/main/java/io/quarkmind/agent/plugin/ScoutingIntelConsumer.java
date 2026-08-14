package io.quarkmind.agent.plugin;

import io.casehub.platform.api.preferences.Preferences;

import java.util.Set;

public interface ScoutingIntelConsumer {
    Set<ScoutingIntelType> subscribedIntelTypes();

    /** Hot-reload hook — called by ScoutingIntelBroker.refreshAll(). Default: no-op. */
    default void refreshSubscriptions(Preferences prefs) {}
}
