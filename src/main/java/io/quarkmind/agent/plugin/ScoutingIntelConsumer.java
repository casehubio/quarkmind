package io.quarkmind.agent.plugin;

import java.util.Set;

public interface ScoutingIntelConsumer {
    Set<ScoutingIntelType> subscribedIntelTypes();
}
